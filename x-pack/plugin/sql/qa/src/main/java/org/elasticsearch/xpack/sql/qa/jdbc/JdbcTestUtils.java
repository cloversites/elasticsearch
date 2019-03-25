/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.sql.qa.jdbc;

import org.apache.logging.log4j.Logger;
import org.elasticsearch.common.SuppressForbidden;
import org.elasticsearch.common.collect.Tuple;
import org.elasticsearch.common.io.PathUtils;
import org.elasticsearch.common.regex.Regex;
import org.elasticsearch.xpack.sql.action.BasicFormatter;
import org.elasticsearch.xpack.sql.proto.ColumnInfo;
import org.elasticsearch.xpack.sql.proto.StringUtils;

import java.io.IOException;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.net.URLConnection;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.jar.JarInputStream;
import java.util.zip.ZipEntry;

import static java.util.Arrays.asList;
import static java.util.Collections.list;
import static org.elasticsearch.xpack.sql.action.BasicFormatter.FormatOption.CLI;

public abstract class JdbcTestUtils {

    public static final String SQL_TRACE = "org.elasticsearch.xpack.sql:TRACE";

    public static final String JDBC_TIMEZONE = "timezone";
    
    public static ZoneId UTC = ZoneId.of("Z");

    public static void logResultSetMetadata(ResultSet rs, Logger logger) throws SQLException {
        ResultSetMetaData metaData = rs.getMetaData();
        // header
        StringBuilder sb = new StringBuilder();
        StringBuilder column = new StringBuilder();

        int columns = metaData.getColumnCount();
        for (int i = 1; i <= columns; i++) {
            if (i > 1) {
                sb.append(" | ");
            }
            column.setLength(0);
            column.append(metaData.getColumnName(i));
            column.append("(");
            column.append(metaData.getColumnTypeName(i));
            column.append(")");

            sb.append(trimOrPad(column));
        }

        int l = sb.length();
        logger.info(sb.toString());
        sb.setLength(0);
        for (int i = 0; i < l; i++) {
            sb.append("-");
        }

        logger.info(sb.toString());
    }

    private static final int MAX_WIDTH = 20;

    public static void logResultSetData(ResultSet rs, Logger log) throws SQLException {
        ResultSetMetaData metaData = rs.getMetaData();
        StringBuilder sb = new StringBuilder();
        StringBuilder column = new StringBuilder();

        int columns = metaData.getColumnCount();

        while (rs.next()) {
            sb.setLength(0);
            for (int i = 1; i <= columns; i++) {
                column.setLength(0);
                if (i > 1) {
                    sb.append(" | ");
                }
                sb.append(trimOrPad(column.append(rs.getString(i))));
            }
            log.info(sb);
        }
    }

    public static String resultSetCurrentData(ResultSet rs) throws SQLException {
        ResultSetMetaData metaData = rs.getMetaData();
        StringBuilder column = new StringBuilder();

        int columns = metaData.getColumnCount();

        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= columns; i++) {
            column.setLength(0);
            if (i > 1) {
                sb.append(" | ");
            }
            sb.append(trimOrPad(column.append(rs.getString(i))));
        }
        return sb.toString();
    }

    private static StringBuilder trimOrPad(StringBuilder buffer) {
        if (buffer.length() > MAX_WIDTH) {
            buffer.setLength(MAX_WIDTH - 1);
            buffer.append("~");
        }
        else {
            for (int i = buffer.length(); i < MAX_WIDTH; i++) {
                buffer.append(" ");
            }
        }
        return buffer;
    }

    public static void logLikeCLI(ResultSet rs, Logger logger) throws SQLException {
        ResultSetMetaData metaData = rs.getMetaData();
        int columns = metaData.getColumnCount();

        List<ColumnInfo> cols = new ArrayList<>(columns);

        for (int i = 1; i <= columns; i++) {
            cols.add(new ColumnInfo(metaData.getTableName(i), metaData.getColumnName(i), metaData.getColumnTypeName(i),
                    metaData.getColumnDisplaySize(i)));
        }


        List<List<Object>> data = new ArrayList<>();

        while (rs.next()) {
            List<Object> entry = new ArrayList<>(columns);
            for (int i = 1; i <= columns; i++) {
                entry.add(rs.getObject(i));
            }
            data.add(entry);
        }

        BasicFormatter formatter = new BasicFormatter(cols, data, CLI);
        logger.info("\n" + formatter.formatWithHeader(cols, data));
    }
    
    public static String of(long millis, String zoneId) {
        return StringUtils.toString(ZonedDateTime.ofInstant(Instant.ofEpochMilli(millis), ZoneId.of(zoneId)));
    }

    /**
     * Returns the classpath resources matching a simple pattern ("*.csv").
     * It supports folders separated by "/" (e.g. "/some/folder/*.txt").
     * 
     * Currently able to resolve resources inside the classpath either from:
     * folders in the file-system (typically IDEs) or
     * inside jars (gradle).
     */
    public static List<URL> classpathResources(String pattern) throws Exception {
        ClassLoader cl = JdbcTestUtils.class.getClassLoader();

        while (pattern.startsWith("/")) {
            pattern = pattern.substring(1);
        }

        Tuple<String, String> split = pathAndName(pattern);

        // the root folder searched inside the classpath - default is the root classpath
        // default file match
        final String root = split.v1();
        final String filePattern = split.v2();

        List<URL> resources = null;

        if (cl instanceof URLClassLoader) {
            resources = asList(((URLClassLoader) cl).getURLs());
        } else {
            // fallback in case of non-standard CL
            resources = list(cl.getResources(root));
        }

        List<URL> matches = new ArrayList<>();

        for (URL resource : resources) {
            String protocol = resource.getProtocol();
            URI uri = resource.toURI();
            Path path = PathUtils.get(uri);

            if ("file".equals(protocol) == false) {
                throw new IllegalArgumentException("Unsupported protocol " + protocol);
            }

            // check whether we're dealing with a jar
            // Java 7 java.nio.fileFileSystem can be used on top of ZIPs/JARs but consumes more memory
            // hence the use of the JAR API
            if (path.toString().endsWith(".jar")) {
                try (JarInputStream jar = getJarStream(resource)) {
                    ZipEntry entry = null;
                    while ((entry = jar.getNextEntry()) != null) {
                        String name = entry.getName();
                        Tuple<String, String> entrySplit = pathAndName(name);
                        if (root.equals(entrySplit.v1()) && Regex.simpleMatch(filePattern, entrySplit.v2())) {
                            matches.add(new URL("jar:" + resource.toString() + "!/" + name));
                        }
                    }
                }
            }
            // normal file access
            else {
                Files.walkFileTree(path, EnumSet.allOf(FileVisitOption.class), 1, new SimpleFileVisitor<Path>() {
                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                        if (Regex.simpleMatch(filePattern, file.toString())) {
                            matches.add(file.toUri().toURL());
                        }
                        return FileVisitResult.CONTINUE;
                    }
                });
            }
        }
        return matches;
    }

    @SuppressForbidden(reason = "need to open jar")
    private static JarInputStream getJarStream(URL resource) throws IOException {
        URLConnection con = resource.openConnection();
        con.setDefaultUseCaches(false);
        return new JarInputStream(con.getInputStream());
    }

    static Tuple<String, String> pathAndName(String string) {
        String folder = StringUtils.EMPTY;
        String file = string;
        int lastIndexOf = string.lastIndexOf("/");
        if (lastIndexOf > 0) {
            folder = string.substring(0, lastIndexOf - 1);
            if (lastIndexOf + 1 < string.length()) {
                file = string.substring(lastIndexOf + 1);
            }
        }
        return new Tuple<>(folder, file);
    }
}