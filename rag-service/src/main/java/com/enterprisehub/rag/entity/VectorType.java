package com.enterprisehub.rag.entity;

import com.pgvector.PGvector;
import org.hibernate.engine.spi.SharedSessionContractImplementor;
import org.hibernate.usertype.UserType;

import java.io.Serializable;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.Arrays;

/**
 * Maps DocumentChunk.embedding (float[]) to Postgres's `vector` column type.
 * pgvector-java ships the JDBC-level PGvector type (constructs from a
 * float[], parses from the wire text form, see PGvector.toArray()) but not a
 * ready-made Hibernate 6 UserType -- this is that glue, written directly
 * against org.hibernate.usertype.UserType rather than a driver-level type
 * registration (PGvector.addVectorType(Connection)), since the app's
 * DataSource is wrapped by TenantAwareDataSource and pooled by Hikari: there
 * is no single reliable point to register a type against every physical
 * connection Hikari creates, and setObject/getString below need no such
 * registration to begin with -- PGvector already extends PGobject, so
 * pgjdbc sends it correctly as an untyped literal Postgres casts against the
 * column's own declared type. Registered per-field via @Type(VectorType.class)
 * (Hibernate 6 style), not @JdbcTypeCode -- that annotation selects a
 * built-in JdbcType by SqlTypes constant; this is a genuinely custom one.
 */
public class VectorType implements UserType<float[]> {

    @Override
    public int getSqlType() {
        return Types.OTHER;
    }

    @Override
    public Class<float[]> returnedClass() {
        return float[].class;
    }

    @Override
    public boolean equals(float[] x, float[] y) {
        return Arrays.equals(x, y);
    }

    @Override
    public int hashCode(float[] x) {
        return Arrays.hashCode(x);
    }

    @Override
    public float[] nullSafeGet(ResultSet rs, int position, SharedSessionContractImplementor session, Object owner) throws SQLException {
        Object value = rs.getObject(position);
        if (value == null) {
            return null;
        }
        // Whatever concrete type the driver hands back for an unregistered
        // `vector` OID (PGobject in practice, sometimes a plain String
        // depending on driver version), its toString() is the same pgvector
        // wire text form ("[1,2,3]") PGvector's String constructor parses --
        // no registerTypes()/addVectorType() call needed on this connection.
        return new PGvector(value.toString()).toArray();
    }

    @Override
    public void nullSafeSet(PreparedStatement st, float[] value, int index, SharedSessionContractImplementor session) throws SQLException {
        st.setObject(index, value == null ? null : new PGvector(value));
    }

    @Override
    public float[] deepCopy(float[] value) {
        return value == null ? null : value.clone();
    }

    @Override
    public boolean isMutable() {
        return true;
    }

    @Override
    public Serializable disassemble(float[] value) {
        return deepCopy(value);
    }

    @Override
    public float[] assemble(Serializable cached, Object owner) {
        return deepCopy((float[]) cached);
    }
}
