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

package me.lucko.luckperms.common.storage.implementation.sql.connection.hikari;

import com.zaxxer.hikari.HikariConfig;
import me.lucko.luckperms.common.storage.misc.StorageCredentials;

import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

public class MariaDbConnectionFactory extends HikariConnectionFactory {
    public MariaDbConnectionFactory(StorageCredentials configuration) {
        super(configuration);
    }

    @Override
    public String getImplementationName() {
        return "MariaDB";
    }

    @Override
    protected String defaultPort() {
        return "3306";
    }

    @Override
    protected void configureDatabase(HikariConfig config, String address, String port, String databaseName, String username, String password) {
        config.setDriverClassName("org.mariadb.jdbc.Driver");
        config.setJdbcUrl("jdbc:mariadb://" + address + ":" + port + "/" + databaseName);
        config.setUsername(username);
        config.setPassword(password);
    }

    @Override
    protected void postInitialize() {
        super.postInitialize();

        // Calling Class.forName("org.mariadb.jdbc.Driver") is enough to call the static initializer
        // which makes our driver available in DriverManager. We don't want that, so unregister it after
        // the pool has been setup.
        deregisterDriver("org.mariadb.jdbc.Driver");
    }

    @Override
    public Function<String, String> getStatementProcessor() {
        return s -> s.replace('\'', '`'); // use backticks for quotes
    }
}
