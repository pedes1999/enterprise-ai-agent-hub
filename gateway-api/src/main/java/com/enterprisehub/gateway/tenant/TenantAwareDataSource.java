package com.enterprisehub.gateway.tenant;

import org.springframework.jdbc.datasource.DelegatingDataSource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

/**
 * Bridges {@link TenantContext} (Java-side, per-thread) to Postgres RLS
 * (DB-side, per-connection) by setting the session variable RLS policies
 * key off as the very first thing that happens on every JDBC connection
 * checkout:
 *
 *     SELECT set_config('app.current_tenant_id', ?, false)
 *
 * `false` (not transaction-local) is correct here specifically BECAUSE this
 * runs at connection-checkout time, not "first statement of a transaction"
 * time -- for JPA/Hibernate's default connection-handling, one physical
 * connection is checked out per EntityManager/transaction and returned to
 * the pool when it ends, so this still ends up scoped to one transaction in
 * practice. Critically, it runs unconditionally on EVERY checkout (setting
 * an empty string when TenantContext is unset, never skipping the call) so
 * a connection previously used by tenant A can never leak that context to
 * whichever request borrows it next from the pool -- fail closed, not
 * "whatever the last user left behind."
 *
 * This replaced an earlier @Aspect-based approach (TenantSessionAspect)
 * that tried to run set_config as the first statement of a @Transactional
 * method via AOP ordering. That broke silently for Spring Data repository
 * methods: repositories get their own dedicated transactional proxy via
 * TransactionalRepositoryProxyPostProcessor, which isn't reliably orderable
 * against a separate @Aspect bean the way two @Transactional methods on
 * regular @Service beans would be. Hooking at the JDBC connection level
 * sidesteps that whole class of proxy-ordering fragility -- it doesn't
 * matter which proxy machinery got the transaction started, only that this
 * class controls the actual Connection object before Hibernate ever gets
 * to use it.
 */
public class TenantAwareDataSource extends DelegatingDataSource {

    private static final String SET_TENANT_SQL = "SELECT set_config('app.current_tenant_id', ?, false)";

    public TenantAwareDataSource(DataSource targetDataSource) {
        super(targetDataSource);
    }

    @Override
    public Connection getConnection() throws SQLException {
        return applyTenantContext(super.getConnection());
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        return applyTenantContext(super.getConnection(username, password));
    }

    private Connection applyTenantContext(Connection connection) throws SQLException {
        String tenantId = TenantContext.get();
        try (PreparedStatement statement = connection.prepareStatement(SET_TENANT_SQL)) {
            statement.setString(1, tenantId == null ? "" : tenantId);
            statement.execute();
        }
        return connection;
    }
}
