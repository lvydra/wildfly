/*
 * Copyright The WildFly Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.jboss.as.test.integration.jca.datasource;

import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.ADD;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.OP;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.OP_ADDR;
import static org.jboss.as.test.shared.PermissionUtils.createPermissionsXmlAsset;

import java.io.FilePermission;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import jakarta.annotation.Resource;
import javax.sql.DataSource;
import jakarta.transaction.UserTransaction;

import org.jboss.arquillian.container.test.api.Deployment;
import org.jboss.arquillian.junit.Arquillian;
import org.jboss.as.arquillian.api.ServerSetup;
import org.jboss.as.arquillian.container.ManagementClient;
import org.jboss.as.test.integration.jca.JcaMgmtBase;
import org.jboss.as.test.integration.jca.JcaMgmtServerSetupTask;
import org.jboss.as.test.integration.management.base.AbstractMgmtTestBase;
import org.jboss.as.test.integration.management.base.ContainerResourceMgmtTestBase;
import org.jboss.dmr.ModelNode;
import org.jboss.remoting3.security.RemotingPermission;
import org.jboss.shrinkwrap.api.Archive;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.asset.StringAsset;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(Arquillian.class)
@ServerSetup(MySQLReauthPluginTransactionTestCase.MySQLReauthServerSetupTask.class)
public class MySQLReauthPluginTransactionTestCase {

    private static final String MYSQL_DS_NAME = "MySQLReauthTestDS";
    private static final String MYSQL_DRIVER = "mysql";

    // MySQL connection configuration
    private static final String MYSQL_CONNECTION_URL = "jdbc:mysql://localhost:3306/testdb";
    private static final String MYSQL_USER1 = "testuser1";
    private static final String MYSQL_PASSWORD1 = "testpass1";
    private static final String MYSQL_USER2 = "testuser2";
    private static final String MYSQL_PASSWORD2 = "testpass2";

    static class MySQLReauthServerSetupTask extends JcaMgmtServerSetupTask {

        @Override
        protected void doSetup(ManagementClient managementClient) throws Exception {
            if (!isMySQLDriverAvailable(managementClient)) {
                return;
            }

            ModelNode address = new ModelNode();
            address.add("subsystem", "datasources");
            address.add("data-source", MYSQL_DS_NAME);

            ModelNode operation = new ModelNode();
            operation.get(OP).set(ADD);
            operation.get(OP_ADDR).set(address);
            operation.get("jndi-name").set("java:jboss/datasources/" + MYSQL_DS_NAME);
            operation.get("use-java-context").set("true");
            operation.get("driver-name").set(MYSQL_DRIVER);
            operation.get("enabled").set("true");
            operation.get("user-name").set(MYSQL_USER1);
            operation.get("password").set(MYSQL_PASSWORD1);
            operation.get("jta").set("true");
            operation.get("use-ccm").set("true");
            operation.get("connection-url").set(MYSQL_CONNECTION_URL);

            operation.get("allow-multiple-users").set("true");

            operation.get("reauth-plugin-class-name").set("org.jboss.jca.adapters.jdbc.extensions.mysql.MySQLReauthPlugin");

            try {
                managementClient.getControllerClient().execute(operation);
                reload();
            } catch (Exception e) {
                System.err.println("Failed to create MySQL datasource: " + e.getMessage());
            }
        }

        private boolean isMySQLDriverAvailable(ManagementClient managementClient) throws Exception {
            ModelNode address = new ModelNode();
            address.add("subsystem", "datasources");

            ModelNode operation = new ModelNode();
            operation.get(OP).set("installed-drivers-list");
            operation.get(OP_ADDR).set(address);

            try {
                ModelNode result = managementClient.getControllerClient().execute(operation);
                if (result.hasDefined("result")) {
                    for (ModelNode driver : result.get("result").asList()) {
                        if (driver.hasDefined("driver-name") &&
                            MYSQL_DRIVER.equals(driver.get("driver-name").asString())) {
                            return true;
                        }
                    }
                }
            } catch (Exception e) {
                System.err.println("Error checking for MySQL driver: " + e.getMessage());
            }
            return false;
        }
    }

    @Resource(mappedName = "java:jboss/datasources/" + MYSQL_DS_NAME)
    private DataSource mysqlDS;

    @Resource(mappedName = "java:jboss/UserTransaction")
    private UserTransaction transaction;

    @Deployment
    public static Archive<?> getDeployment() {
        JavaArchive jar = ShrinkWrap.create(JavaArchive.class, "mysql-reauth-test.jar");
        jar.addClasses(
                MySQLReauthPluginTransactionTestCase.class,
                Datasource.class,
                JcaMgmtBase.class,
                ContainerResourceMgmtTestBase.class,
                AbstractMgmtTestBase.class,
                JcaMgmtServerSetupTask.class);
        jar.addAsManifestResource(new StringAsset(
                "Dependencies: javax.inject.api,org.jboss.as.connector," +
                    "org.jboss.staxmapper,  " +
                    "org.jboss.ironjacamar.impl, " +
                    "org.jboss.ironjacamar.jdbcadapters\n"
        ), "MANIFEST.MF");

        jar.addAsManifestResource(createPermissionsXmlAsset(
                new RemotingPermission("createEndpoint"),
                new RemotingPermission("connect"),
                new FilePermission(System.getProperty("jboss.inst") + "/standalone/tmp/auth/*", "read")
        ), "permissions.xml");

        return jar;
    }

    @Test
    public void testReauthPluginDuringTransaction() throws Exception {
        Assume.assumeNotNull("MySQL datasource not available - skipping test", mysqlDS);

        try {
            try (Connection conn = mysqlDS.getConnection()) {
                Assume.assumeNotNull("Cannot get MySQL connection - skipping test", conn);
            }
        } catch (SQLException e) {
            Assume.assumeNoException("MySQL not available - skipping test", e);
        }

        transaction.begin();

        try (Connection connection1 = mysqlDS.getConnection()) {
            try (PreparedStatement statement = connection1.prepareStatement("SELECT 1")) {
                statement.execute();
            }

            try (Connection connection2 = mysqlDS.getConnection(MYSQL_USER2, MYSQL_PASSWORD2)) {
                try (PreparedStatement statement = connection2.prepareStatement("SELECT 2")) {
                    statement.execute();
                }
            }
        }

        transaction.commit();
    }
}
