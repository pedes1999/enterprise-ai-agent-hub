package com.enterprisehub.gateway.tenant;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class TenantAwareDataSourceTest {

    @AfterEach
    void cleanup() {
        TenantContext.clear();
    }

    @Test
    void getConnection_setsCurrentTenantId_whenContextIsSet() throws Exception {
        DataSource delegate = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        when(delegate.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(anyString())).thenReturn(statement);

        TenantContext.set("tenant-abc");
        Connection result = new TenantAwareDataSource(delegate).getConnection();

        assertThat(result).isSameAs(connection);
        verify(statement).setString(1, "tenant-abc");
        verify(statement).execute();
    }

    @Test
    void getConnection_setsEmptyString_whenContextIsUnset_notSkipped() throws Exception {
        DataSource delegate = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        when(delegate.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(anyString())).thenReturn(statement);

        new TenantAwareDataSource(delegate).getConnection();

        verify(statement).setString(1, "");
        verify(statement).execute();
    }

    @Test
    void getConnection_alwaysRunsSetConfig_evenIfPreviousBorrowerLeftContext() throws Exception {
        // Simulates a pooled connection reused across requests -- the
        // wrapper must overwrite, not skip, on every single checkout.
        DataSource delegate = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        when(delegate.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        TenantAwareDataSource dataSource = new TenantAwareDataSource(delegate);

        TenantContext.set("tenant-a");
        dataSource.getConnection();
        TenantContext.clear();
        dataSource.getConnection();

        verify(statement, times(2)).execute();
        verify(statement).setString(1, "tenant-a");
        verify(statement).setString(1, "");
    }

    @Test
    void getConnectionWithCredentials_alsoAppliesTenantContext() throws Exception {
        DataSource delegate = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        when(delegate.getConnection("user", "pass")).thenReturn(connection);
        when(connection.prepareStatement(anyString())).thenReturn(statement);

        TenantContext.set("tenant-xyz");
        new TenantAwareDataSource(delegate).getConnection("user", "pass");

        verify(statement).setString(1, "tenant-xyz");
    }
}
