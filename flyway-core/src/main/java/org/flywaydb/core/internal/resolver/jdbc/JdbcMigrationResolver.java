/**
 * Copyright 2010-2015 Axel Fontaine
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.flywaydb.core.internal.resolver.jdbc;

import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.MigrationType;
import org.flywaydb.core.api.MigrationVersion;
import org.flywaydb.core.api.migration.MigrationChecksumProvider;
import org.flywaydb.core.api.migration.MigrationInfoProvider;
import org.flywaydb.core.api.migration.jdbc.JdbcMigration;
import org.flywaydb.core.api.resolver.MigrationResolver;
import org.flywaydb.core.api.resolver.ResolvedMigration;
import org.flywaydb.core.internal.resolver.MigrationInfoHelper;
import org.flywaydb.core.internal.resolver.ResolvedMigrationComparator;
import org.flywaydb.core.internal.resolver.ResolvedMigrationImpl;
import org.flywaydb.core.internal.util.ClassUtils;
import org.flywaydb.core.internal.util.Location;
import org.flywaydb.core.internal.util.Pair;
import org.flywaydb.core.internal.util.StringUtils;
import org.flywaydb.core.internal.util.scanner.Scanner;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Migration resolver for Jdbc migrations. The classes must have a name like V1 or V1_1_3 or V1__Description
 * or V1_1_3__Description.
 */
public class JdbcMigrationResolver implements MigrationResolver {
    /**
     * The base package on the classpath where to migrations are located.
     */
    private final Location location;

    /**
     * The ClassLoader to use.
     */
    private ClassLoader classLoader;

    private final String javaMigrationPrefix;
    private final String javaMigrationSeparator;

    /**
     * Creates a new instance.
     *
     * @param location    The base package on the classpath where to migrations are located.
     * @param classLoader The ClassLoader for loading migrations on the classpath.
     */
    public JdbcMigrationResolver(ClassLoader classLoader, Location location, String javaMigrationPrefix, String javaMigrationSeparator) {
        this.location = location;
        this.classLoader = classLoader;
        this.javaMigrationPrefix = javaMigrationPrefix;
        this.javaMigrationSeparator = javaMigrationSeparator;
    }

    public List<ResolvedMigration> resolveMigrations() {
        List<ResolvedMigration> migrations = new ArrayList<ResolvedMigration>();

        if (!location.isClassPath()) {
            return migrations;
        }

        try {
            Class<?>[] classes = new Scanner(classLoader).scanForClasses(location, JdbcMigration.class);
            for (Class<?> clazz : classes) {
                JdbcMigration jdbcMigration = ClassUtils.instantiate(clazz.getName(), classLoader);

                ResolvedMigrationImpl migrationInfo = extractMigrationInfo(jdbcMigration);
                migrationInfo.setPhysicalLocation(ClassUtils.getLocationOnDisk(clazz));
                migrationInfo.setExecutor(new JdbcMigrationExecutor(jdbcMigration));

                migrations.add(migrationInfo);
            }
        } catch (Exception e) {
            throw new FlywayException("Unable to resolve Jdbc Java migrations in location: " + location, e);
        }

        Collections.sort(migrations, new ResolvedMigrationComparator());
        return migrations;
    }

    /**
     * Extracts the migration info from this migration.
     *
     * @param jdbcMigration The migration to analyse.
     * @return The migration info.
     */
    /* private -> testing */ ResolvedMigrationImpl extractMigrationInfo(JdbcMigration jdbcMigration) {
        Integer checksum = null;
        if (jdbcMigration instanceof MigrationChecksumProvider) {
            MigrationChecksumProvider checksumProvider = (MigrationChecksumProvider) jdbcMigration;
            checksum = checksumProvider.getChecksum();
        }

        MigrationVersion version;
        String description;
        if (jdbcMigration instanceof MigrationInfoProvider) {
            MigrationInfoProvider infoProvider = (MigrationInfoProvider) jdbcMigration;
            version = infoProvider.getVersion();
            description = infoProvider.getDescription();
            if (!StringUtils.hasText(description)) {
                throw new FlywayException("Missing description for migration " + version);
            }
        } else {
            Pair<MigrationVersion, String> info =
                    MigrationInfoHelper.extractVersionAndDescription(
                            ClassUtils.getShortName(jdbcMigration.getClass()), javaMigrationPrefix, javaMigrationSeparator, "");
            version = info.getLeft();
            description = info.getRight();
        }

        String script = jdbcMigration.getClass().getName();


        ResolvedMigrationImpl resolvedMigration = new ResolvedMigrationImpl();
        resolvedMigration.setVersion(version);
        resolvedMigration.setDescription(description);
        resolvedMigration.setScript(script);
        resolvedMigration.setChecksum(checksum);
        resolvedMigration.setType(MigrationType.JDBC);
        return resolvedMigration;
    }
}
