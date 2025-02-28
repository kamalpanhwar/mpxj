/*
 * file:       MPD9DatabaseReader.java
 * author:     Jon Iles
 * copyright:  (c) Packwood Software 2007
 * date:       2006-02-02
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

package net.sf.mpxj.mpd;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.sql.DataSource;

import net.sf.mpxj.MPXJException;
import net.sf.mpxj.ProjectCalendar;
import net.sf.mpxj.ProjectConfig;
import net.sf.mpxj.ProjectFile;
import net.sf.mpxj.SubProject;
import net.sf.mpxj.Task;
import net.sf.mpxj.common.AutoCloseableHelper;
import net.sf.mpxj.common.NumberHelper;
import net.sf.mpxj.common.ResultSetHelper;
import net.sf.mpxj.listener.ProjectListener;

/**
 * This class reads project data from an MPD9 format database.
 */
public final class MPD9DatabaseReader extends MPD9AbstractReader
{
   /**
    * Add a listener to receive events as a project is being read.
    *
    * @param listener ProjectListener instance
    */
   public void addProjectListener(ProjectListener listener)
   {
      if (m_projectListeners == null)
      {
         m_projectListeners = new ArrayList<>();
      }
      m_projectListeners.add(listener);
   }

   /**
    * Populates a Map instance representing the IDs and names of
    * projects available in the current database.
    *
    * @return Map instance containing ID and name pairs
    */
   public Map<Integer, String> listProjects() throws MPXJException
   {
      try
      {
         Map<Integer, String> result = new HashMap<>();

         List<ResultSetRow> rows = getRows("SELECT PROJ_ID, PROJ_NAME FROM MSP_PROJECTS");
         for (ResultSetRow row : rows)
         {
            processProjectListItem(result, row);
         }

         return result;
      }

      catch (SQLException ex)
      {
         throw new MPXJException(MPXJException.READ_ERROR, ex);
      }
   }

   /**
    * Read a project from the current data source.
    *
    * @return ProjectFile instance
    */
   public ProjectFile read() throws MPXJException
   {
      try
      {
         m_project = new ProjectFile();
         m_eventManager = m_project.getEventManager();

         ProjectConfig config = m_project.getProjectConfig();
         config.setAutoTaskID(false);
         config.setAutoTaskUniqueID(false);
         config.setAutoResourceID(false);
         config.setAutoResourceUniqueID(false);
         config.setAutoOutlineLevel(false);
         config.setAutoOutlineNumber(false);
         config.setAutoWBS(false);
         config.setAutoCalendarUniqueID(false);
         config.setAutoAssignmentUniqueID(false);

         m_project.getProjectProperties().setFileApplication("Microsoft");
         m_project.getProjectProperties().setFileType("MPD");

         m_project.getEventManager().addProjectListeners(m_projectListeners);

         processProjectProperties();
         processCalendars();
         processResources();
         processResourceBaselines();
         processTasks();
         processTaskBaselines();
         processLinks();
         processAssignments();
         processAssignmentBaselines();
         processExtendedAttributes();
         processSubProjects();
         postProcessing();

         return (m_project);
      }

      catch (SQLException ex)
      {
         throw new MPXJException(MPXJException.READ_ERROR, ex);
      }

      finally
      {
         reset();

         if (m_allocatedConnection)
         {
            AutoCloseableHelper.closeQuietly(m_connection);
            m_connection = null;
         }
      }
   }

   /**
    * Select the project properties from the database.
    */
   private void processProjectProperties() throws SQLException
   {
      List<ResultSetRow> rows = getRows("SELECT * FROM MSP_PROJECTS WHERE PROJ_ID=?", m_projectID);
      if (!rows.isEmpty())
      {
         processProjectProperties(rows.get(0));
      }
   }

   /**
    * Select calendar data from the database.
    */
   private void processCalendars() throws SQLException
   {
      for (ResultSetRow row : getRows("SELECT * FROM MSP_CALENDARS WHERE PROJ_ID=?", m_projectID))
      {
         processCalendar(row);
      }

      updateBaseCalendarNames();

      processCalendarData(m_project.getCalendars());

      m_project.getProjectProperties().setDefaultCalendar(m_project.getCalendars().getByName(m_defaultCalendarName));
   }

   /**
    * Process calendar hours and exception data from the database.
    *
    * @param calendars all calendars for the project
    */
   private void processCalendarData(List<ProjectCalendar> calendars) throws SQLException
   {
      for (ProjectCalendar calendar : calendars)
      {
         processCalendarData(calendar, getRows("SELECT * FROM MSP_CALENDAR_DATA WHERE PROJ_ID=? AND CAL_UID=?", m_projectID, calendar.getUniqueID()));
      }
   }

   /**
    * Process the hours and exceptions for an individual calendar.
    *
    * @param calendar project calendar
    * @param calendarData hours and exception rows for this calendar
    */
   private void processCalendarData(ProjectCalendar calendar, List<ResultSetRow> calendarData)
   {
      for (ResultSetRow row : calendarData)
      {
         processCalendarData(calendar, row);
      }
   }

   /**
    * Process resources.
    */
   private void processResources() throws SQLException
   {
      for (ResultSetRow row : getRows("SELECT * FROM MSP_RESOURCES WHERE PROJ_ID=?", m_projectID))
      {
         processResource(row);
      }
   }

   /**
    * Process resource baseline values.
    */
   private void processResourceBaselines() throws SQLException
   {
      if (m_hasResourceBaselines)
      {
         for (ResultSetRow row : getRows("SELECT * FROM MSP_RESOURCE_BASELINES WHERE PROJ_ID=?", m_projectID))
         {
            processResourceBaseline(row);
         }
      }
   }

   /**
    * Process tasks.
    */
   private void processTasks() throws SQLException
   {
      for (ResultSetRow row : getRows("SELECT * FROM MSP_TASKS WHERE PROJ_ID=?", m_projectID))
      {
         processTask(row);
      }
   }

   /**
    * Process task baseline values.
    */
   private void processTaskBaselines() throws SQLException
   {
      if (m_hasTaskBaselines)
      {
         for (ResultSetRow row : getRows("SELECT * FROM MSP_TASK_BASELINES WHERE PROJ_ID=?", m_projectID))
         {
            processTaskBaseline(row);
         }
      }
   }

   /**
    * Process links.
    */
   private void processLinks() throws SQLException
   {
      for (ResultSetRow row : getRows("SELECT * FROM MSP_LINKS WHERE PROJ_ID=?", m_projectID))
      {
         processLink(row);
      }
   }

   /**
    * Process resource assignments.
    */
   private void processAssignments() throws SQLException
   {
      for (ResultSetRow row : getRows("SELECT * FROM MSP_ASSIGNMENTS WHERE PROJ_ID=?", m_projectID))
      {
         processAssignment(row);
      }
   }

   /**
    * Process resource assignment baseline values.
    */
   private void processAssignmentBaselines() throws SQLException
   {
      if (m_hasAssignmentBaselines)
      {
         for (ResultSetRow row : getRows("SELECT * FROM MSP_ASSIGNMENT_BASELINES WHERE PROJ_ID=?", m_projectID))
         {
            processAssignmentBaseline(row);
         }
      }
   }

   /**
    * This method reads the extended task and resource attributes.
    */
   private void processExtendedAttributes() throws SQLException
   {
      processTextFields();
      processNumberFields();
      processFlagFields();
      processDurationFields();
      processDateFields();
      processOutlineCodeFields();
   }

   /**
    * The only indication that a task is a SubProject is the contents
    * of the subproject file name field. We test these here then add a skeleton
    * subproject structure to match the way we do things with MPP files.
    */
   private void processSubProjects()
   {
      int subprojectIndex = 1;
      for (Task task : m_project.getTasks())
      {
         String subProjectFileName = task.getSubprojectName();
         if (subProjectFileName != null)
         {
            String fileName = subProjectFileName;
            int offset = 0x01000000 + (subprojectIndex * 0x00400000);
            int index = subProjectFileName.lastIndexOf('\\');
            if (index != -1)
            {
               fileName = subProjectFileName.substring(index + 1);
            }

            SubProject sp = new SubProject();
            sp.setFileName(fileName);
            sp.setFullPath(subProjectFileName);
            sp.setUniqueIDOffset(Integer.valueOf(offset));
            sp.setTaskUniqueID(task.getUniqueID());
            task.setSubProject(sp);

            ++subprojectIndex;
         }
      }
   }

   /**
    * Reads text field extended attributes.
    */
   private void processTextFields() throws SQLException
   {
      for (ResultSetRow row : getRows("SELECT * FROM MSP_TEXT_FIELDS WHERE PROJ_ID=?", m_projectID))
      {
         processTextField(row);
      }
   }

   /**
    * Reads number field extended attributes.
    */
   private void processNumberFields() throws SQLException
   {
      for (ResultSetRow row : getRows("SELECT * FROM MSP_NUMBER_FIELDS WHERE PROJ_ID=?", m_projectID))
      {
         processNumberField(row);
      }
   }

   /**
    * Reads flag field extended attributes.
    */
   private void processFlagFields() throws SQLException
   {
      for (ResultSetRow row : getRows("SELECT * FROM MSP_FLAG_FIELDS WHERE PROJ_ID=?", m_projectID))
      {
         processFlagField(row);
      }
   }

   /**
    * Reads duration field extended attributes.
    */
   private void processDurationFields() throws SQLException
   {
      for (ResultSetRow row : getRows("SELECT * FROM MSP_DURATION_FIELDS WHERE PROJ_ID=?", m_projectID))
      {
         processDurationField(row);
      }
   }

   /**
    * Reads date field extended attributes.
    */
   private void processDateFields() throws SQLException
   {
      for (ResultSetRow row : getRows("SELECT * FROM MSP_DATE_FIELDS WHERE PROJ_ID=?", m_projectID))
      {
         processDateField(row);
      }
   }

   /**
    * Process outline code fields.
    */
   private void processOutlineCodeFields() throws SQLException
   {
      for (ResultSetRow row : getRows("SELECT * FROM MSP_CODE_FIELDS WHERE PROJ_ID=?", m_projectID))
      {
         processOutlineCodeFields(row);
      }
   }

   /**
    * Process a single outline code.
    *
    * @param parentRow outline code to task mapping table
    */
   private void processOutlineCodeFields(Row parentRow) throws SQLException
   {
      Integer entityID = parentRow.getInteger("CODE_REF_UID");
      Integer outlineCodeEntityID = parentRow.getInteger("CODE_UID");

      for (ResultSetRow row : getRows("SELECT * FROM MSP_OUTLINE_CODES WHERE CODE_UID=?", outlineCodeEntityID))
      {
         processOutlineCodeField(entityID, row);
      }
   }

   /**
    * Retrieve a number of rows matching the supplied query.
    *
    * @param sql query statement
    * @return result set
    */
   private List<ResultSetRow> getRows(String sql) throws SQLException
   {
      allocateConnection();
      try (PreparedStatement ps = m_connection.prepareStatement(sql))
      {
         try (ResultSet rs = ps.executeQuery())
         {
            Map<String, Integer> meta = ResultSetHelper.populateMetaData(rs);
            List<ResultSetRow> result = new ArrayList<>();
            while (rs.next())
            {
               result.add(new ResultSetRow(rs, meta));
            }
            return result;
         }
      }
   }

   /**
    * Retrieve a number of rows matching the supplied query
    * which takes a single parameter.
    *
    * @param sql query statement
    * @param var bind variable value
    * @return result set
    */
   private List<ResultSetRow> getRows(String sql, Integer var) throws SQLException
   {
      allocateConnection();

      List<ResultSetRow> result = new ArrayList<>();

      try (PreparedStatement ps = m_connection.prepareStatement(sql))
      {
         ps.setInt(1, NumberHelper.getInt(var));
         try (ResultSet rs = ps.executeQuery())
         {
            Map<String, Integer> meta = ResultSetHelper.populateMetaData(rs);
            while (rs.next())
            {
               result.add(new ResultSetRow(rs, meta));
            }
            return result;
         }
      }
   }

   /**
    * Retrieve a number of rows matching the supplied query
    * which takes two parameters.
    *
    * @param sql query statement
    * @param var1 bind variable value
    * @param var2 bind variable value
    * @return result set
    */
   private List<ResultSetRow> getRows(String sql, Integer var1, Integer var2) throws SQLException
   {
      allocateConnection();

      try (PreparedStatement ps = m_connection.prepareStatement(sql))
      {
         ps.setInt(1, NumberHelper.getInt(var1));
         ps.setInt(2, NumberHelper.getInt(var2));
         try (ResultSet rs = ps.executeQuery())
         {
            List<ResultSetRow> result = new ArrayList<>();
            Map<String, Integer> meta = ResultSetHelper.populateMetaData(rs);
            while (rs.next())
            {
               result.add(new ResultSetRow(rs, meta));
            }
            return result;
         }
      }
   }

   /**
    * Allocates a database connection.
    */
   private void allocateConnection() throws SQLException
   {
      if (m_connection == null)
      {
         m_connection = m_dataSource.getConnection();
         m_allocatedConnection = true;
         queryDatabaseMetaData();
      }
   }

   /**
    * Sets the data source used to read the project data.
    *
    * @param dataSource data source
    */
   public void setDataSource(DataSource dataSource)
   {
      m_dataSource = dataSource;
   }

   /**
    * Sets the connection to be used to read the project data.
    *
    * @param connection database connection
    */
   public void setConnection(Connection connection)
   {
      m_connection = connection;
      queryDatabaseMetaData();
   }

   /**
    * Queries database metadata to check for the existence of
    * specific tables.
    */
   private void queryDatabaseMetaData()
   {
      try
      {
         Set<String> tables = new HashSet<>();
         DatabaseMetaData dmd = m_connection.getMetaData();
         try (ResultSet rs = dmd.getTables(null, null, null, null))
         {
            while (rs.next())
            {
               tables.add(rs.getString("TABLE_NAME"));
            }
         }
         m_hasResourceBaselines = tables.contains("MSP_RESOURCE_BASELINES");
         m_hasTaskBaselines = tables.contains("MSP_TASK_BASELINES");
         m_hasAssignmentBaselines = tables.contains("MSP_ASSIGNMENT_BASELINES");
      }

      catch (Exception ex)
      {
         // Ignore errors when reading metadata
      }
   }

   private DataSource m_dataSource;
   private boolean m_allocatedConnection;
   private Connection m_connection;
   private List<ProjectListener> m_projectListeners;
   private boolean m_hasResourceBaselines;
   private boolean m_hasTaskBaselines;
   private boolean m_hasAssignmentBaselines;
}