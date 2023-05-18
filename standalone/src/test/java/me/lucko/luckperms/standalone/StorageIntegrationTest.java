/*
 * This file is part of LuckPerms, licensed under the MIT License.
 *
 *  Copyright (c) lucko (Luck) <luck@lucko.me>
 *  Copyright (c) contributors
 *
 *  Permission is hereby granted, free of charge, to any person obtaining a copy
 *  of this software and associated documentation files (the "Software"), to deal
 *  in the Software without restriction, including without limitation the rights
 *  to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 *  copies of the Software, and to permit persons to whom the Software is
 *  furnished to do so, subject to the following conditions:
 *
 *  The above copyright notice and this permission notice shall be included in all
 *  copies or substantial portions of the Software.
 *
 *  THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 *  IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 *  FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 *  AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 *  LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 *  OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 *  SOFTWARE.
 */

package me.lucko.luckperms.standalone;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import me.lucko.luckperms.common.model.Group;
import me.lucko.luckperms.common.model.User;
import me.lucko.luckperms.common.node.types.Inheritance;
import me.lucko.luckperms.common.node.types.Meta;
import me.lucko.luckperms.common.node.types.Permission;
import me.lucko.luckperms.common.node.types.Prefix;
import me.lucko.luckperms.standalone.app.LuckPermsApplication;
import me.lucko.luckperms.standalone.app.integration.HealthReporter;
import me.lucko.luckperms.standalone.utils.TestPluginBootstrap;
import me.lucko.luckperms.standalone.utils.TestPluginBootstrap.TestPlugin;
import me.lucko.luckperms.standalone.utils.TestPluginProvider;
import net.luckperms.api.event.cause.CreationCause;
import net.luckperms.api.model.data.DataType;
import net.luckperms.api.node.Node;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.Month;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
public class StorageIntegrationTest {

    private static final Node TEST_PERMISSION_1 = Permission.builder()
            .permission("example.permission")
            .build();

    private static final Node TEST_PERMISSION_2 = Permission.builder()
            .permission("test")
            .value(false)
            .expiry(LocalDate.of(2050, Month.APRIL, 1).atStartOfDay().toInstant(ZoneOffset.UTC))
            .withContext("server", "foo")
            .withContext("world", "bar")
            .withContext("test", "test")
            .build();

    private static final Node TEST_GROUP = Inheritance.builder()
            .group("default")
            .value(false)
            .expiry(LocalDate.of(2050, Month.APRIL, 1).atStartOfDay().toInstant(ZoneOffset.UTC))
            .withContext("server", "foo")
            .withContext("world", "bar")
            .withContext("test", "test")
            .build();

    private static final Node TEST_PREFIX = Prefix.builder()
            .priority(100)
            .prefix("TEST")
            .withContext("server", "foo")
            .withContext("world", "bar")
            .build();

    private static final Node TEST_META = Meta.builder()
            .key("foo")
            .value("bar")
            .build();


    private static void testStorage(LuckPermsApplication app, TestPluginBootstrap bootstrap, TestPlugin plugin) {
        // check the plugin is healthy
        HealthReporter.Health health = app.getHealthReporter().poll();
        assertNotNull(health);
        assertTrue(health.isUp());

        // try to create / save a group
        Group group = plugin.getStorage().createAndLoadGroup("test", CreationCause.INTERNAL).join();
        group.setNode(DataType.NORMAL, TEST_PERMISSION_1, true);
        group.setNode(DataType.NORMAL, TEST_PERMISSION_2, true);
        group.setNode(DataType.NORMAL, TEST_GROUP, true);
        group.setNode(DataType.NORMAL, TEST_PREFIX, true);
        group.setNode(DataType.NORMAL, TEST_META, true);
        plugin.getStorage().saveGroup(group).join();

        // try to create / save a user
        UUID exampleUniqueId = UUID.fromString("c1d60c50-70b5-4722-8057-87767557e50d");
        plugin.getStorage().savePlayerData(exampleUniqueId, "Luck").join();
        User user = plugin.getStorage().loadUser(exampleUniqueId, "Luck").join();
        user.setNode(DataType.NORMAL, TEST_PERMISSION_1, true);
        user.setNode(DataType.NORMAL, TEST_PERMISSION_2, true);
        user.setNode(DataType.NORMAL, TEST_GROUP, true);
        user.setNode(DataType.NORMAL, TEST_PREFIX, true);
        user.setNode(DataType.NORMAL, TEST_META, true);
        plugin.getStorage().saveUser(user).join();

        plugin.getStorage().loadAllGroups().join();

        Group testGroup = plugin.getGroupManager().getIfLoaded("test");
        assertNotNull(testGroup);
        assertEquals(ImmutableSet.of(TEST_PERMISSION_1, TEST_PERMISSION_2, TEST_GROUP, TEST_PREFIX, TEST_META), testGroup.normalData().asSet());

        User testUser = plugin.getStorage().loadUser(exampleUniqueId, null).join();
        assertNotNull(testUser);
        assertEquals(ImmutableSet.of(Inheritance.builder("default").build(), TEST_PERMISSION_1, TEST_PERMISSION_2, TEST_GROUP, TEST_PREFIX, TEST_META), testUser.normalData().asSet());
    }

    @Nested
    @Tag("docker")
    class MySql {

        @Container
        private final GenericContainer<?> container = new GenericContainer<>(DockerImageName.parse("mysql:8"))
                .withEnv("MYSQL_DATABASE", "minecraft")
                .withEnv("MYSQL_ROOT_PASSWORD", "passw0rd")
                .withExposedPorts(3306);

        @Test
        public void testMySql(@TempDir Path tempDir) {
            assertTrue(this.container.isRunning());

            String host = this.container.getHost();
            Integer port = this.container.getFirstMappedPort();

            Map<String, String> config = ImmutableMap.<String, String>builder()
                    .put("storage-method", "mysql")
                    .put("data.address", host + ":" + port)
                    .put("data.database", "minecraft")
                    .put("data.username", "root")
                    .put("data.password", "passw0rd")
                    .build();

            TestPluginProvider.use(tempDir, config, StorageIntegrationTest::testStorage);
        }
    }

    @Nested
    @Tag("docker")
    class MariaDb {

        @Container
        private final GenericContainer<?> container = new GenericContainer<>(DockerImageName.parse("mariadb"))
                .withEnv("MARIADB_USER", "minecraft")
                .withEnv("MARIADB_PASSWORD", "passw0rd")
                .withEnv("MARIADB_ROOT_PASSWORD", "rootpassw0rd")
                .withEnv("MARIADB_DATABASE", "minecraft")
                .withExposedPorts(3306);

        @Test
        public void testMariaDb(@TempDir Path tempDir) {
            assertTrue(this.container.isRunning());

            String host = this.container.getHost();
            Integer port = this.container.getFirstMappedPort();

            Map<String, String> config = ImmutableMap.<String, String>builder()
                    .put("storage-method", "mariadb")
                    .put("data.address", host + ":" + port)
                    .put("data.database", "minecraft")
                    .put("data.username", "minecraft")
                    .put("data.password", "passw0rd")
                    .build();

            TestPluginProvider.use(tempDir, config, StorageIntegrationTest::testStorage);
        }
    }

    @Nested
    @Tag("docker")
    class Postgres {

        @Container
        private final GenericContainer<?> container = new GenericContainer<>(DockerImageName.parse("postgres"))
                .withEnv("POSTGRES_PASSWORD", "passw0rd")
                .withExposedPorts(5432);

        @Test
        public void testPostgres(@TempDir Path tempDir) {
            assertTrue(this.container.isRunning());

            String host = this.container.getHost();
            Integer port = this.container.getFirstMappedPort();

            Map<String, String> config = ImmutableMap.<String, String>builder()
                    .put("storage-method", "postgresql")
                    .put("data.address", host + ":" + port)
                    .put("data.database", "postgres")
                    .put("data.username", "postgres")
                    .put("data.password", "passw0rd")
                    .build();

            TestPluginProvider.use(tempDir, config, StorageIntegrationTest::testStorage);
        }
    }

    @Nested
    @Tag("docker")
    class MongoDb {

        @Container
        private final GenericContainer<?> container = new GenericContainer<>(DockerImageName.parse("mongo"))
                .withExposedPorts(27017);

        @Test
        public void testMongo(@TempDir Path tempDir) {
            assertTrue(this.container.isRunning());

            String host = this.container.getHost();
            Integer port = this.container.getFirstMappedPort();

            Map<String, String> config = ImmutableMap.<String, String>builder()
                    .put("storage-method", "mongodb")
                    .put("data.address", host + ":" + port)
                    .put("data.database", "minecraft")
                    .put("data.username", "")
                    .put("data.password", "")
                    .build();

            TestPluginProvider.use(tempDir, config, StorageIntegrationTest::testStorage);
        }
    }

    @Nested
    class FlatFile {

        @Test
        public void testYaml(@TempDir Path tempDir) throws IOException {
            TestPluginProvider.use(tempDir, ImmutableMap.of("storage-method", "yaml"), StorageIntegrationTest::testStorage);

            Path storageDir = tempDir.resolve("yaml-storage");
            compareFiles(storageDir, "example/yaml", "groups/default.yml");
            compareFiles(storageDir, "example/yaml", "groups/test.yml");
            compareFiles(storageDir, "example/yaml", "users/c1d60c50-70b5-4722-8057-87767557e50d.yml");
        }

        @Test
        public void testJson(@TempDir Path tempDir) throws IOException {
            TestPluginProvider.use(tempDir, ImmutableMap.of("storage-method", "json"), StorageIntegrationTest::testStorage);

            Path storageDir = tempDir.resolve("json-storage");
            compareFiles(storageDir, "example/json", "groups/default.json");
            compareFiles(storageDir, "example/json", "groups/test.json");
            compareFiles(storageDir, "example/json", "users/c1d60c50-70b5-4722-8057-87767557e50d.json");
        }

        @Test
        public void testHocon(@TempDir Path tempDir) throws IOException {
            TestPluginProvider.use(tempDir, ImmutableMap.of("storage-method", "hocon"), StorageIntegrationTest::testStorage);

            Path storageDir = tempDir.resolve("hocon-storage");
            compareFiles(storageDir, "example/hocon", "groups/default.conf");
            compareFiles(storageDir, "example/hocon", "groups/test.conf");
            compareFiles(storageDir, "example/hocon", "users/c1d60c50-70b5-4722-8057-87767557e50d.conf");
        }

        @Test
        public void testToml(@TempDir Path tempDir) throws IOException {
            TestPluginProvider.use(tempDir, ImmutableMap.of("storage-method", "toml"), StorageIntegrationTest::testStorage);

            Path storageDir = tempDir.resolve("toml-storage");
            compareFiles(storageDir, "example/toml", "groups/default.toml");
            compareFiles(storageDir, "example/toml", "groups/test.toml");
            compareFiles(storageDir, "example/toml", "users/c1d60c50-70b5-4722-8057-87767557e50d.toml");
        }

        @Test
        public void testYamlCombined(@TempDir Path tempDir) throws IOException {
            TestPluginProvider.use(tempDir, ImmutableMap.of("storage-method", "yaml-combined"), StorageIntegrationTest::testStorage);

            Path storageDir = tempDir.resolve("yaml-storage");
            compareFiles(storageDir, "example/yaml-combined", "groups.yml");
            compareFiles(storageDir, "example/yaml-combined", "tracks.yml");
            compareFiles(storageDir, "example/yaml-combined", "users.yml");
        }

        @Test
        public void testJsonCombined(@TempDir Path tempDir) throws IOException {
            TestPluginProvider.use(tempDir, ImmutableMap.of("storage-method", "json-combined"), StorageIntegrationTest::testStorage);

            Path storageDir = tempDir.resolve("json-storage");
            compareFiles(storageDir, "example/json-combined", "groups.json");
            compareFiles(storageDir, "example/json-combined", "tracks.json");
            compareFiles(storageDir, "example/json-combined", "users.json");
        }

        @Test
        public void testHoconCombined(@TempDir Path tempDir) throws IOException {
            TestPluginProvider.use(tempDir, ImmutableMap.of("storage-method", "hocon-combined"), StorageIntegrationTest::testStorage);

            Path storageDir = tempDir.resolve("hocon-storage");
            compareFiles(storageDir, "example/hocon-combined", "groups.conf");
            compareFiles(storageDir, "example/hocon-combined", "tracks.conf");
            compareFiles(storageDir, "example/hocon-combined", "users.conf");
        }

        @Test
        public void testTomlCombined(@TempDir Path tempDir) throws IOException {
            TestPluginProvider.use(tempDir, ImmutableMap.of("storage-method", "toml-combined"), StorageIntegrationTest::testStorage);

            Path storageDir = tempDir.resolve("toml-storage");
            compareFiles(storageDir, "example/toml-combined", "groups.toml");
            compareFiles(storageDir, "example/toml-combined", "tracks.toml");
            compareFiles(storageDir, "example/toml-combined", "users.toml");
        }

        private static void compareFiles(Path dir, String examplePath, String file) throws IOException {
            String exampleFile = examplePath + "/" + file;

            String expected;
            try (InputStream in = StorageIntegrationTest.class.getClassLoader().getResourceAsStream(exampleFile)) {
                if (in == null) {
                    throw new IOException("File does not exist: " + exampleFile);
                }
                expected = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }

            String actual = Files.readString(dir.resolve(Paths.get(file)));
            assertEquals(expected.trim(), actual.trim());
        }

    }

}
