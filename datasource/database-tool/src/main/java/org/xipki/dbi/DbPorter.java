/*
 * Copyright (c) 2014 Lijun Liao
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
 * limitations under the License
 *
 */

package org.xipki.dbi;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;

import org.xipki.database.api.DataSource;
import org.xipki.security.common.ParamChecker;

/**
 * @author Lijun Liao
 */

public class DbPorter
{
    public static final String FILENAME_CA_Configuration = "CA-Configuration.xml";
    public static final String FILENAME_CA_CertStore = "CA-CertStore.xml";
    public static final String FILENAME_OCSP_CertStore = "OCSP-CertStore.xml";
    public static final String DIRNAME_CRL = "CRL";
    public static final String DIRNAME_CERT = "CERT";
    public static final String PREFIX_FILENAME_CERTS = "certs-";

    public static final int VERSION = 1;

    private final DataSource dataSource;
    private Connection connection;

    protected final String baseDir;

    public DbPorter(DataSource dataSource, String baseDir)
    throws SQLException
    {
        super();
        ParamChecker.assertNotNull("dataSource", dataSource);
        ParamChecker.assertNotEmpty("baseDir", baseDir);

        this.dataSource = dataSource;
        this.connection = this.dataSource.getConnection();
        this.baseDir = baseDir;
    }

    protected static void setLong(PreparedStatement ps, int index, Long i)
    throws SQLException
    {
        if(i != null)
        {
            ps.setLong(index, i.longValue());
        }
        else
        {
            ps.setNull(index, Types.BIGINT);
        }
    }

    protected static void setInt(PreparedStatement ps, int index, Integer i)
    throws SQLException
    {
        if(i != null)
        {
            ps.setInt(index, i.intValue());
        }
        else
        {
            ps.setNull(index, Types.INTEGER);
        }
    }

    protected static void setBoolean(PreparedStatement ps, int index, boolean b)
    throws SQLException
    {
        ps.setInt(index, b ? 1 : 0);
    }

    protected Statement createStatement()
    throws SQLException
    {
        return connection.createStatement();
    }

    protected PreparedStatement prepareStatement(String sql)
    throws SQLException
    {
        return connection.prepareStatement(sql);
    }

    protected void releaseResources(Statement ps, ResultSet rs)
    {
        if(ps != null)
        {
            try
            {
                ps.close();
            }catch(SQLException e)
            {
            }
        }

        if(rs != null)
        {
            try
            {
                rs.close();
            }catch(SQLException e)
            {
            }
        }
    }

    public void shutdown()
    {
        dataSource.returnConnection(connection);
        connection = null;
    }

    public int getMin(String table, String column)
    throws SQLException
    {
        return dataSource.getMin(connection, table, column);
    }

    public int getMax(String table, String column)
    throws SQLException
    {
        return dataSource.getMax(connection, table, column);
    }

    public boolean tableHasColumn(String table, String column)
    throws SQLException
    {
        return dataSource.tableHasColumn(connection, table, column);
    }

    public boolean tableExists(String table)
    throws SQLException
    {
        return dataSource.tableExists(connection, table);
    }
}
