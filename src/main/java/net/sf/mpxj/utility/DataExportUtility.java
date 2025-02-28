/*
 * file:       DataExportUtility.java
 * author:     Jon Iles
 * copyright:  (c) Packwood Software 2011
 * date:       05/04/2011
 */

/*
 * This library is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as published by the
 * Free Software Foundation; either version 2.1 of the License, or (at your
 * option) any later version.
 *
 * This library is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY
 * or FITNESS FOR A PARTICULAR PURPOSE. See the GNU Lesser General Public
 * License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this library; if not, write to the Free Software Foundation, Inc.,
 * 59 Temple Place, Suite 330, Boston, MA 02111-1307, USA.
 */

package net.sf.mpxj.utility;

import java.io.FileWriter;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Timestamp;
import java.sql.Types;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

import net.sf.mpxj.common.AutoCloseableHelper;
import net.sf.mpxj.common.JdbcOdbcHelper;

/**
 * Simple utility to export data to an XML file from an arbitrary database
 * schema.
 */
public final class DataExportUtility
{
   /**
    * Command line entry point.
    *
    * @param argv command line arguments
    */
   public static void main(String[] argv)
   {
      if (argv.length != 2)
      {
         System.out.println("DataExport <filename> <output directory>");
      }
      else
      {
         Connection connection = null;

         try
         {
            connection = DriverManager.getConnection(JdbcOdbcHelper.getMicrosoftAccessJdbcUrl(argv[0]));

            DataExportUtility dx = new DataExportUtility();
            dx.process(connection, argv[1]);
         }

         catch (Exception ex)
         {
            ex.printStackTrace();
         }

         finally
         {
            AutoCloseableHelper.closeQuietly(connection);
         }
      }
   }

   /**
    * Export data base contents to a directory using supplied connection.
    *
    * @param connection database connection
    * @param directory target directory
    */
   public void process(Connection connection, String directory) throws Exception
   {
      connection.setAutoCommit(true);

      //
      // Retrieve metadata about the connection
      //
      DatabaseMetaData dmd = connection.getMetaData();

      String[] types =
      {
         "TABLE"
      };

      FileWriter fw = new FileWriter(directory);
      PrintWriter pw = new PrintWriter(fw);

      pw.println("<?xml version=\"1.0\" encoding=\"UTF-8\"?>");
      pw.println();
      pw.println("<database>");

      ResultSet tables = dmd.getTables(null, null, null, types);
      while (tables.next())
      {
         processTable(pw, connection, tables.getString("TABLE_NAME"));
      }

      pw.println("</database>");

      pw.close();

      tables.close();
   }

   /**
    * Process a single table.
    *
    * @param pw output print writer
    * @param connection database connection
    * @param name table name
    */
   private void processTable(PrintWriter pw, Connection connection, String name) throws Exception
   {
      System.out.println("Processing " + name);

      //
      // Prepare statement to retrieve all data
      //
      PreparedStatement ps = connection.prepareStatement("select * from " + name);

      //
      // Execute the query
      //
      ResultSet rs = ps.executeQuery();

      //
      // Retrieve column meta data
      //
      ResultSetMetaData rmd = ps.getMetaData();

      int index;
      int columnCount = rmd.getColumnCount();
      String[] columnNames = new String[columnCount];
      int[] columnTypes = new int[columnCount];

      for (index = 0; index < columnCount; index++)
      {
         columnNames[index] = rmd.getColumnName(index + 1);
         columnTypes[index] = rmd.getColumnType(index + 1);
      }

      //
      // Generate the output file
      //
      pw.println("<table name=\"" + name + "\">");

      StringBuilder buffer = new StringBuilder(255);
      DateFormat df = new SimpleDateFormat("dd/MM/yyyy hh:mm", Locale.UK);

      while (rs.next())
      {
         pw.println(" <row>");

         for (index = 0; index < columnCount; index++)
         {
            pw.print("  <column name=\"" + columnNames[index] + "\" type=\"" + columnTypes[index] + "\">");
            switch (columnTypes[index])
            {
               case Types.BINARY:
               case Types.BLOB:
               case Types.LONGVARBINARY:
               case Types.VARBINARY:
               {
                  pw.println("[BINARY DATA]");
                  break;
               }

               case Types.DATE:
               case Types.TIME:
               {
                  Date data = rs.getDate(index + 1);
                  if (data != null)
                  {
                     pw.print(df.format(data));
                  }
                  break;
               }

               case Types.TIMESTAMP:
               {
                  Timestamp data = rs.getTimestamp(index + 1);
                  if (data != null)
                  {
                     pw.print(data);
                  }
                  break;
               }

               default:
               {
                  String data = rs.getString(index + 1);
                  if (data != null)
                  {
                     pw.print(escapeText(buffer, data));
                  }
                  break;
               }
            }
            pw.println("</column>");
         }

         pw.println(" </row>");
      }

      pw.println("</table>");

      ps.close();

   }

   /**
    * Quick and dirty XML text escape.
    *
    * @param sb working string buffer
    * @param text input text
    * @return escaped text
    */
   private String escapeText(StringBuilder sb, String text)
   {
      int length = text.length();
      char c;

      sb.setLength(0);

      for (int loop = 0; loop < length; loop++)
      {
         c = text.charAt(loop);

         switch (c)
         {
            case '<':
            {
               sb.append("&lt;");
               break;
            }

            case '>':
            {
               sb.append("&gt;");
               break;
            }

            case '&':
            {
               sb.append("&amp;");
               break;
            }

            default:
            {
               if (validXMLCharacter(c))
               {
                  if (c > 127)
                  {
                     sb.append("&#").append((int) c).append(";");
                  }
                  else
                  {
                     sb.append(c);
                  }
               }

               break;
            }
         }
      }

      return (sb.toString());
   }

   /**
    * Quick and dirty valid XML character test.
    *
    * @param c input character
    * @return Boolean flag
    */
   private boolean validXMLCharacter(char c)
   {
      return (c == 0x9 || c == 0xA || c == 0xD || (c >= 0x20 && c <= 0xD7FF) || (c >= 0xE000 && c <= 0xFFFD));
   }
}
