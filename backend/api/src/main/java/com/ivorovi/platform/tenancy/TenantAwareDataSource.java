package com.ivorovi.platform.tenancy;

import java.io.Closeable;
import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.DelegatingDataSource;

/**
 * Configures every borrowed connection for the current {@link TenantContext}. Settings are (re)applied on each
 * borrow, including clearing them when there is no context, so a pooled connection can never carry a previous
 * request's tenant.
 */
public class TenantAwareDataSource extends DelegatingDataSource implements Closeable {

    private static final String APPLY =
            "SELECT set_config('app.tenant_id', ?, false), set_config('app.platform_context', ?, false)";

    public TenantAwareDataSource(DataSource target) {
        super(target);
    }

    @Override
    public Connection getConnection() throws SQLException {
        return configure(super.getConnection());
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        return configure(super.getConnection(username, password));
    }

    private Connection configure(Connection connection) throws SQLException {
        String tenant = TenantContext.currentTenantId().map(UUID::toString).orElse("");
        String platform = TenantContext.isPlatformContext() ? "on" : "";
        try (PreparedStatement ps = connection.prepareStatement(APPLY)) {
            ps.setString(1, tenant);
            ps.setString(2, platform);
            ps.execute();
            return connection;
        } catch (SQLException | RuntimeException e) {
            connection.close();
            throw e;
        }
    }

    @Override
    public void close() throws IOException {
        if (getTargetDataSource() instanceof AutoCloseable closeable) {
            try {
                closeable.close();
            } catch (Exception e) {
                throw new IOException(e);
            }
        }
    }
}
