/**
 * Copyright 2012 Miroslav Šulc
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package com.fordfrog.ruian2pgsql.convertors;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UnsupportedEncodingException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

import com.fordfrog.ruian2pgsql.Config;
import com.fordfrog.ruian2pgsql.gml.GMLUtils;
import com.fordfrog.ruian2pgsql.utils.Log;
import com.fordfrog.ruian2pgsql.utils.Namespaces;
import com.fordfrog.ruian2pgsql.utils.XMLUtils;

/**
 * Converts RÚIAN data into PostgreSQL database.
 *
 * @author fordfrog
 */
public class MainConvertor {

//    /**
//     * Exchange format convertor instance.
//     */
//    private static ExchangeFormatConvertor exchangeFormatConvertor;
//
//    /**
//     * Special exchange format convertor instance.
//     */
//    private static SpecialExchangeFormatConvertor specialExchangeFormatConvertor;

    // WorkerCtx.java (put it next to MainConvertor)

    final static class WorkerCtx implements AutoCloseable {
        private final Connection conn;
        private final ExchangeFormatConvertor exch;
        private final SpecialExchangeFormatConvertor spc;

        WorkerCtx(Connection conn) throws SQLException {
            this.conn = conn;
            this.exch = new ExchangeFormatConvertor(conn);
            this.spc = new SpecialExchangeFormatConvertor(conn);
        }

        Connection conn() {
            return conn;
        }

        ExchangeFormatConvertor exch() {
            return exch;
        }

        SpecialExchangeFormatConvertor spc() {
            return spc;
        }

        @Override
        public void close() throws SQLException {
            conn.close();
        }
    }

    /**
     * Creates new instance of MainConvertor.
     */
    private MainConvertor() {
    }

    /**
     * Converts all files with .xml.gz and .xml extensions from specified directory into database.
     *
     * @throws XMLStreamException Thrown if problem occurred while reading XML stream.
     * @throws SQLException       Thrown if problem occurred while communicating with database.
     */
    public static void convert() throws XMLStreamException, SQLException {
        final long startTimestamp = System.currentTimeMillis();

        try (final Connection con = DriverManager.getConnection(Config.getDbConnectionUrl())) {
//            exchangeFormatConvertor = new ExchangeFormatConvertor(con);
//            specialExchangeFormatConvertor = new SpecialExchangeFormatConvertor(con);

            con.setAutoCommit(false);

            if (Config.isCreateTables()) {
                Log.write("Recreating tables...");

                if (Config.isNoGis() || Config.isMysqlDriver()) {
                    if (Config.isMysqlDriver()) {
                        runSQLFromResource(con, "/sql/schema_no_gis_mysql_tbl.sql");
                    } else {
                        runSQLFromResource(con, "/sql/schema_no_gis_tbl.sql");
                    }
                } else {
                    runSQLFromResource(con, "/sql/schema_tbl.sql");
                }
            }

            if (!Config.isNoGis() && !Config.isMysqlDriver()) {
                Log.write("Recreating RÚIAN statistics views...");
                runSQLFromResource(con, "/sql/ruian_stats.sql");
                runSQLFromResource(con, "/sql/ruian_stats_full.sql");
            }

            if (Config.isTruncateAll()) {
                Log.write("Truncating all data tables...");
                runSQLFromResource(con, "/sql/truncate_all.sql");
            }

            if (Config.isResetTransactionIds()) {
                Log.write("Resetting transaction ids...");
                runSQLFromResource(con, "/sql/reset_transaction_ids.sql");
            }

            if (!Config.isNoGis() && !Config.isMysqlDriver() && !Config.isConvertToEWKT()
                    && GMLUtils.checkMultipointBug(con)) {
                Log.write("Installed version of Postgis is affected by " + "multipoint bug "
                        + "http://trac.osgeo.org/postgis/ticket/1928, enabling " + "workaround...");
                GMLUtils.setMultipointBugWorkaround(true);
            }

            con.commit();

//            for (final Path file : getInputFiles(Config.getInputDirPath())) {
//                processFile(file);
//                con.commit();
//            }

            final List<Path> files = getInputFiles(Config.getInputDirPath());

            final int nThreads = Math.min(Config.getThreads(), files.size());
            final ExecutorService pool = Executors.newFixedThreadPool(nThreads, r -> {
                Thread t = new Thread(r, "r2p-" + r.hashCode());
                t.setDaemon(false);
                return t;
            });

            for (Path file : files) {
                pool.submit(() -> {
                    try (WorkerCtx ctx = new WorkerCtx(DriverManager.getConnection(Config.getDbConnectionUrl()))) {
                        ctx.conn().setAutoCommit(false);
                        processFile(file, ctx);
                        ctx.conn().commit();
                    } catch (Exception ex) {
                        Log.write("ERROR processing " + file + ": " + ex.getMessage());
                        throw new RuntimeException(ex);
                    }
                });
            }

            pool.shutdown();
            pool.awaitTermination(Long.MAX_VALUE, TimeUnit.HOURS);

            if (Config.isCreateTables()) {
                Log.write("Creating indexes...");

                if (Config.isNoGis() || Config.isMysqlDriver()) {
                    if (Config.isMysqlDriver()) {
                        runSQLFromResource(con, "/sql/schema_no_gis_mysql_idx.sql");
                    } else {
                        runSQLFromResource(con, "/sql/schema_no_gis_idx.sql");
                    }
                } else {
                    runSQLFromResource(con, "/sql/schema_idx.sql");
                }
            }

            Log.write("Total duration: " + (System.currentTimeMillis() - startTimestamp) + " ms");
        } catch (InterruptedException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }
    }

    /**
     * Runs SQL statements from specified resource.
     *
     * @param con          database connection
     * @param resourceName name of the resource from which to read the SQL statements
     */
    private static void runSQLFromResource(final Connection con, final String resourceName) {
        if (Config.isDryRun()) {
            return;
        }

        final StringBuilder sbSQL = new StringBuilder(10_240);

        try (final BufferedReader reader = new BufferedReader(
                new InputStreamReader(MainConvertor.class.getResourceAsStream(resourceName), "UTF-8"));
                final Statement stm = con.createStatement()) {
            String line = reader.readLine();

            while (line != null) {
                sbSQL.append(line);

                if (line.endsWith(";")) {
                    stm.execute(sbSQL.toString());
                    sbSQL.setLength(0);
                } else {
                    sbSQL.append('\n');
                }

                line = reader.readLine();
            }
        } catch (final SQLException ex) {
            throw new RuntimeException("Statement failed: " + sbSQL.toString(), ex);
        } catch (final IOException ex) {
            throw new RuntimeException("Failed to read SQL statements from resource", ex);
        }
    }

    /**
     * Reads input files from input directory and sorts them in ascending order.
     *
     * @param inputDirPath input directory
     *
     * @return sorted list of input files
     */
    private static List<Path> getInputFiles(final Path inputDirPath) {
        final List<Path> result = new ArrayList<>(10);

        try (final DirectoryStream<Path> files = Files.newDirectoryStream(inputDirPath)) {
            final Iterator<Path> filesIterator = files.iterator();

            while (filesIterator.hasNext()) {
                final Path file = filesIterator.next();

                if (!Files.isDirectory(file)) {
                    result.add(file);
                }
            }
        } catch (final IOException ex) {
            throw new RuntimeException("Failed to read content of input directory", ex);
        }

        Collections.sort(result, new Comparator<Path>() {
            @Override
            public int compare(final Path o1, final Path o2) {
                return o1.getFileName().toString().compareTo(o2.getFileName().toString());
            }
        });

        return result;
    }

    /**
     * Processes single input file.
     *
     * @param file file path
     *
     * @throws UnsupportedEncodingException Thrown if UTF-8 encoding is not supported.
     * @throws XMLStreamException           Thrown if problem occurred while reading XML stream.
     * @throws SQLException                 Thrown if problem occurred while communicating with database.
     */
    private static void processFile(final Path file, final WorkerCtx ctx) throws XMLStreamException, SQLException {
        final String fileName = file.toString();

        if (fileName.endsWith(".xml.gz") || fileName.endsWith(".xml.zip") || fileName.endsWith(".xml")) {
            final long startTimestamp = System.currentTimeMillis();

            Log.write("Processing file " + file);
            Log.flush();

            try (final InputStream inputStream = Files.newInputStream(file)) {
                if (fileName.endsWith(".gz")) {
                    readInputStream(new GZIPInputStream(inputStream), ctx);
                } else if (fileName.endsWith(".zip")) {
                    ZipFile zif = new ZipFile(fileName);
                    ZipEntry ze = zif.entries().nextElement();
                    readInputStream(zif.getInputStream(ze), ctx);
                    zif.close();
                } else {
                    readInputStream(inputStream, ctx);
                }
            } catch (final IOException ex) {
                throw new RuntimeException("Failed to read input file", ex);
            }

            Log.write("File processed in " + (System.currentTimeMillis() - startTimestamp) + " ms");
            Log.flush();
        } else {
            Log.write("Unsupported file extension, ignoring file " + file);
        }
    }

    /**
     * Reads input stream and processes the XML content.
     *
     * @param inputStream input stream containing XML data
     *
     * @throws XMLStreamException Thrown if problem occurred while reading XML stream.
     * @throws SQLException       Thrown if problem occurred while communicating with database.
     */
    private static void readInputStream(final InputStream inputStream, final WorkerCtx ctx)
            throws XMLStreamException, SQLException {
        final XMLInputFactory xMLInputFactory = XMLInputFactory.newInstance();

        final XMLStreamReader reader;

        try {
            reader = xMLInputFactory.createXMLStreamReader(new InputStreamReader(inputStream, "UTF-8"));
        } catch (final UnsupportedEncodingException ex) {
            throw new RuntimeException("UTF-8 encoding is not supported", ex);
        }

        while (reader.hasNext()) {
            final int event = reader.next();

            if (event == XMLStreamReader.START_ELEMENT) {
                processElement(reader, ctx);
            }
        }
    }

    /**
     * Processes elements and its sub-elements.
     *
     * @param reader XML stream reader
     *
     * @throws XMLStreamException Thrown if problem occurred while reading XML stream.
     * @throws SQLException       Thrown if problem occurred while communicating with database.
     */
    private static void processElement(final XMLStreamReader reader, final WorkerCtx ctx)
            throws XMLStreamException, SQLException {
        switch (reader.getNamespaceURI()) {
        case Namespaces.VYMENNY_FORMAT_TYPY:
            switch (reader.getLocalName()) {
            case "VymennyFormat":
//                exchangeFormatConvertor.convert(reader);
                ctx.exch().convert(reader);
                break;
            default:
                XMLUtils.processUnsupported(reader);
            }
            break;
        case Namespaces.SPECIALNI_VYMENNY_FORMAT_TYPY:
            switch (reader.getLocalName()) {
            case "SpecialniVymennyFormat":
//                specialExchangeFormatConvertor.convert(reader);
                ctx.spc().convert(reader);
                break;
            default:
                XMLUtils.processUnsupported(reader);
            }
            break;
        default:
            XMLUtils.processUnsupported(reader);
        }
    }
}
