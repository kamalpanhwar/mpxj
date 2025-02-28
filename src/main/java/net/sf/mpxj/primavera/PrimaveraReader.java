/*
 * file:       PrimaveraReader.java
 * author:     Jon Iles
 * copyright:  (c) Packwood Software 2010
 * date:       22/03/2010
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

package net.sf.mpxj.primavera;

import java.awt.Color;
import java.text.DateFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

import net.sf.mpxj.AccrueType;
import net.sf.mpxj.ActivityCode;
import net.sf.mpxj.ActivityCodeContainer;
import net.sf.mpxj.ActivityCodeScope;
import net.sf.mpxj.ActivityCodeValue;
import net.sf.mpxj.ActivityStatus;
import net.sf.mpxj.ActivityType;
import net.sf.mpxj.AssignmentField;
import net.sf.mpxj.Availability;
import net.sf.mpxj.ConstraintType;
import net.sf.mpxj.CostAccount;
import net.sf.mpxj.CostAccountContainer;
import net.sf.mpxj.CostRateTable;
import net.sf.mpxj.CostRateTableEntry;
import net.sf.mpxj.CriticalActivityType;
import net.sf.mpxj.CurrencySymbolPosition;
import net.sf.mpxj.DataType;
import net.sf.mpxj.DateRange;
import net.sf.mpxj.Day;
import net.sf.mpxj.Duration;
import net.sf.mpxj.EventManager;
import net.sf.mpxj.ExpenseCategory;
import net.sf.mpxj.ExpenseCategoryContainer;
import net.sf.mpxj.ExpenseItem;
import net.sf.mpxj.FieldContainer;
import net.sf.mpxj.FieldType;
import net.sf.mpxj.FieldTypeClass;
import net.sf.mpxj.HtmlNotes;
import net.sf.mpxj.Notes;
import net.sf.mpxj.ParentNotes;
import net.sf.mpxj.PercentCompleteType;
import net.sf.mpxj.Priority;
import net.sf.mpxj.ProjectCalendar;
import net.sf.mpxj.ProjectCalendarException;
import net.sf.mpxj.ProjectCalendarHours;
import net.sf.mpxj.ProjectConfig;
import net.sf.mpxj.ProjectFile;
import net.sf.mpxj.ProjectProperties;
import net.sf.mpxj.Rate;
import net.sf.mpxj.Relation;
import net.sf.mpxj.RelationType;
import net.sf.mpxj.Resource;
import net.sf.mpxj.ResourceAssignment;
import net.sf.mpxj.ResourceField;
import net.sf.mpxj.ResourceType;
import net.sf.mpxj.StructuredNotes;
import net.sf.mpxj.Task;
import net.sf.mpxj.TaskField;
import net.sf.mpxj.TaskType;
import net.sf.mpxj.TimeUnit;
import net.sf.mpxj.WorkContour;
import net.sf.mpxj.common.BooleanHelper;
import net.sf.mpxj.common.DateHelper;
import net.sf.mpxj.common.NumberHelper;

/**
 * This class provides a generic front end to read project data from
 * a database.
 */
final class PrimaveraReader
{
   /**
    * Constructor.
    *
    * @param taskUdfCounters UDF counters for tasks
    * @param resourceUdfCounters UDF counters for resources
    * @param assignmentUdfCounters UDF counters for assignments
    * @param resourceFields resource field mapping
    * @param wbsFields wbs field mapping
    * @param taskFields task field mapping
    * @param assignmentFields assignment field mapping
    * @param roleFields role field mapping
    * @param matchPrimaveraWBS determine WBS behaviour
    * @param wbsIsFullPath determine the WBS attribute structure
    */
   public PrimaveraReader(UserFieldCounters taskUdfCounters, UserFieldCounters resourceUdfCounters, UserFieldCounters assignmentUdfCounters, Map<FieldType, String> resourceFields, Map<FieldType, String> roleFields, Map<FieldType, String> wbsFields, Map<FieldType, String> taskFields, Map<FieldType, String> assignmentFields, boolean matchPrimaveraWBS, boolean wbsIsFullPath)
   {
      m_project = new ProjectFile();
      m_eventManager = m_project.getEventManager();

      ProjectConfig config = m_project.getProjectConfig();
      config.setAutoTaskUniqueID(false);
      config.setAutoResourceUniqueID(false);
      config.setAutoAssignmentUniqueID(false);
      config.setAutoWBS(false);
      config.setBaselineStrategy(new PrimaveraBaselineStrategy());

      m_resourceFields = resourceFields;
      m_roleFields = roleFields;
      m_wbsFields = wbsFields;
      m_taskFields = taskFields;
      m_assignmentFields = assignmentFields;

      m_taskUdfCounters = taskUdfCounters;
      m_taskUdfCounters.reset();
      m_resourceUdfCounters = resourceUdfCounters;
      m_resourceUdfCounters.reset();
      m_assignmentUdfCounters = assignmentUdfCounters;
      m_assignmentUdfCounters.reset();

      m_matchPrimaveraWBS = matchPrimaveraWBS;
      m_wbsIsFullPath = wbsIsFullPath;
   }

   /**
    * Retrieves the project data read from this file.
    *
    * @return project data
    */
   public ProjectFile getProject()
   {
      return m_project;
   }

   /**
    * Retrieves a list of external predecessors relationships.
    *
    * @return list of external predecessors
    */
   public List<ExternalRelation> getExternalRelations()
   {
      return m_externalRelations;
   }

   /**
    * Process project properties.
    *
    * @param rows project properties data.
    */
   public void processProjectProperties(List<Row> rows)
   {
      if (!rows.isEmpty())
      {
         Row row = rows.get(0);
         ProjectProperties properties = m_project.getProjectProperties();
         properties.setBaselineProjectUniqueID(row.getInteger("sum_base_proj_id"));
         properties.setCreationDate(row.getDate("create_date"));
         properties.setCriticalActivityType(CRITICAL_ACTIVITY_MAP.getOrDefault(row.getString("critical_path_type"), CriticalActivityType.TOTAL_FLOAT));
         properties.setGUID(row.getUUID("guid"));
         properties.setProjectID(row.getString("proj_short_name"));
         properties.setName(row.getString("proj_short_name")); // Temporary, updated later from the WBS
         properties.setDefaultTaskType(TASK_TYPE_MAP.get(row.getString("def_duration_type")));
         properties.setStatusDate(row.getDate("last_recalc_date"));
         properties.setFiscalYearStartMonth(row.getInteger("fy_start_month_num"));
         properties.setUniqueID(row.getInteger("proj_id"));
         properties.setExportFlag(row.getBoolean("export_flag"));
         properties.setPlannedStart(row.getDate("plan_start_date"));
         properties.setScheduledFinish(row.getDate("scd_end_date"));
         properties.setMustFinishBy(row.getDate("plan_end_date"));
         // cannot assign actual calendar yet as it has not been read yet

         m_defaultCalendarID = row.getInteger("clndr_id");
      }
   }

   /**
    * Process expense categories.
    *
    * @param categories expense categories
    */
   public void processExpenseCategories(List<Row> categories)
   {
      ExpenseCategoryContainer container = m_project.getExpenseCategories();
      categories.forEach(row -> container.add(new ExpenseCategory(row.getInteger("cost_type_id"), row.getString("cost_type"), row.getInteger("seq_num"))));
   }

   /**
    * Process cost accounts.
    *
    * @param accounts cost accounts
    */
   public void processCostAccounts(List<Row> accounts)
   {
      CostAccountContainer container = m_project.getCostAccounts();
      accounts.forEach(row -> container.add(new CostAccount(row.getInteger("acct_id"), row.getString("acct_short_name"), row.getString("acct_name"), row.getString("acct_descr"), row.getInteger("acct_seq_num"))));
      accounts.forEach(row -> container.getByUniqueID(row.getInteger("acct_id")).setParent(container.getByUniqueID(row.getInteger("parent_acct_id"))));
   }

   /**
    * Read activity code types and values.
    *
    * @param types activity code type data
    * @param typeValues activity code value data
    * @param assignments activity code task assignments
    */
   public void processActivityCodes(List<Row> types, List<Row> typeValues, List<Row> assignments)
   {
      ActivityCodeContainer container = m_project.getActivityCodes();
      Map<Integer, ActivityCode> map = new HashMap<>();

      for (Row row : types)
      {
         ActivityCode code = new ActivityCode(row.getInteger("actv_code_type_id"), ACTIVITY_CODE_SCOPE_MAP.get(row.getString("actv_code_type_scope")), row.getInteger("proj_id"), row.getInteger("seq_num"), row.getString("actv_code_type"));
         container.add(code);
         map.put(code.getUniqueID(), code);
      }

      for (Row row : typeValues)
      {
         ActivityCode code = map.get(row.getInteger("actv_code_type_id"));
         if (code != null)
         {
            ActivityCodeValue value = code.addValue(row.getInteger("actv_code_id"), row.getInteger("seq_num"), row.getString("short_name"), row.getString("actv_code_name"), getColor(row.getString("color")));
            m_activityCodeMap.put(value.getUniqueID(), value);
         }
      }

      for (Row row : typeValues)
      {
         ActivityCodeValue child = m_activityCodeMap.get(row.getInteger("actv_code_id"));
         ActivityCodeValue parent = m_activityCodeMap.get(row.getInteger("parent_actv_code_id"));
         if (parent != null && child != null)
         {
            child.setParent(parent);
         }
      }

      for (Row row : assignments)
      {
         Integer taskID = row.getInteger("task_id");
         List<Integer> list = m_activityCodeAssignments.computeIfAbsent(taskID, k -> new ArrayList<>());
         list.add(row.getInteger("actv_code_id"));
      }
   }

   private Color getColor(String value)
   {
      Color result = null;
      if (value != null && value.length() > 0)
      {
         result = new Color(Integer.parseInt(value, 16));
      }
      return result;
   }

   /**
    * Process User Defined Fields (UDF).
    *
    * @param fields field definitions
    * @param values field values
    */
   public void processUserDefinedFields(List<Row> fields, List<Row> values)
   {
      // Process fields
      Map<Integer, String> tableNameMap = new HashMap<>();
      for (Row row : fields)
      {
         Integer fieldId = row.getInteger("udf_type_id");
         String tableName = row.getString("table_name");
         tableNameMap.put(fieldId, tableName);

         FieldTypeClass fieldTypeClass = FIELD_TYPE_MAP.get(tableName);
         if (fieldTypeClass != null)
         {
            String fieldDataType = row.getString("logical_data_type");
            FieldType fieldType = allocateUserDefinedField(fieldTypeClass, UserFieldDataType.valueOf(fieldDataType));
            if (fieldType != null)
            {
               String fieldName = row.getString("udf_type_label");
               m_udfFields.put(fieldId, fieldType);
               m_project.getCustomFields().getCustomField(fieldType).setAlias(fieldName).setUniqueID(fieldId);
            }
         }
      }

      // Process values
      for (Row row : values)
      {
         Integer typeID = row.getInteger("udf_type_id");
         String tableName = tableNameMap.get(typeID);
         Map<Integer, List<Row>> tableData = m_udfValues.computeIfAbsent(tableName, k -> new HashMap<>());

         Integer id = row.getInteger("fk_id");
         List<Row> list = tableData.computeIfAbsent(id, k -> new ArrayList<>());
         list.add(row);
      }
   }

   /**
    * Process project calendars.
    *
    * @param rows project calendar data
    */
   public void processCalendars(List<Row> rows)
   {
      //
      // First pass: read calendar definitions
      //
      Map<ProjectCalendar, Integer> baseCalendarMap = new HashMap<>();
      for (Row row : rows)
      {
         ProjectCalendar calendar = processCalendar(row);
         Integer baseCalendarID = row.getInteger("base_clndr_id");
         if (baseCalendarID != null)
         {
            baseCalendarMap.put(calendar, baseCalendarID);
         }
      }

      //
      // Second pass: create calendar hierarchy
      //
      for (Map.Entry<ProjectCalendar, Integer> entry : baseCalendarMap.entrySet())
      {
         ProjectCalendar baseCalendar = m_project.getCalendarByUniqueID(entry.getValue());
         if (baseCalendar != null)
         {
            entry.getKey().setParent(baseCalendar);
         }
      }

      //
      // We've used Primavera's unique ID values for the calendars we've read so far.
      // At this point any new calendars we create must be auto number. We also need to
      // ensure that the auto numbering starts from an appropriate value.
      //
      ProjectConfig config = m_project.getProjectConfig();
      config.setAutoCalendarUniqueID(true);
      config.updateCalendarUniqueCounter();

      ProjectCalendar defaultCalendar = m_project.getCalendarByUniqueID(m_defaultCalendarID);
      if (defaultCalendar == null)
      {
         defaultCalendar = m_project.getCalendars().findOrCreateDefaultCalendar();
      }
      m_project.setDefaultCalendar(defaultCalendar);
   }

   /**
    * Process data for an individual calendar.
    *
    * @param row calendar data
    * @return ProjectCalendar instance
    */
   public ProjectCalendar processCalendar(Row row)
   {
      ProjectCalendar calendar = m_project.addCalendar();

      Integer id = row.getInteger("clndr_id");
      calendar.setUniqueID(id);
      calendar.setName(row.getString("clndr_name"));
      calendar.setType(CALENDAR_TYPE_MAP.get(row.getString("clndr_type")));
      calendar.setPersonal(row.getBoolean("rsrc_private"));

      if (row.getBoolean("default_flag") && m_defaultCalendarID == null)
      {
         // We don't have a default calendar set for the project, use the global default
         m_defaultCalendarID = id;
      }

      // Process data
      String calendarData = row.getString("clndr_data");
      if (calendarData != null && !calendarData.isEmpty())
      {
         StructuredTextParser parser = new StructuredTextParser();
         parser.setRaiseExceptionOnParseError(false);
         StructuredTextRecord root = parser.parse(calendarData);
         StructuredTextRecord daysOfWeek = root.getChild("DaysOfWeek");
         StructuredTextRecord exceptions = root.getChild("Exceptions");

         if (daysOfWeek == null)
         {
            if (row.getInteger("base_clndr_id") == null)
            {
               // We have a base calendar, but we don't have any days specified.
               // Populate the calendar with a default working week.
               calendar.addDefaultCalendarDays();
               calendar.addDefaultCalendarHours();
            }
         }
         else
         {
            processCalendarDays(calendar, daysOfWeek);
         }

         if (exceptions != null)
         {
            processCalendarExceptions(calendar, exceptions);
         }
      }
      else
      {
         // if there is not DaysOfWeek data, Primavera seems to default to Mon-Fri, 8:00-16:00
         DateRange defaultHourRange = new DateRange(DateHelper.getTime(8, 0), DateHelper.getTime(16, 0));
         for (Day day : Day.values())
         {
            ProjectCalendarHours hours = calendar.addCalendarHours(day);
            if (day != Day.SATURDAY && day != Day.SUNDAY)
            {
               calendar.setWorkingDay(day, true);
               hours.add(defaultHourRange);
            }
            else
            {
               calendar.setWorkingDay(day, false);
            }
         }
      }

      //
      // Try and extract minutes per period from the calendar row
      //
      Double rowHoursPerDay = getHoursPerPeriod(row, "day_hr_cnt");
      Double rowHoursPerWeek = getHoursPerPeriod(row, "week_hr_cnt");
      Double rowHoursPerMonth = getHoursPerPeriod(row, "month_hr_cnt");
      Double rowHoursPerYear = getHoursPerPeriod(row, "year_hr_cnt");

      calendar.setCalendarMinutesPerDay(Integer.valueOf((int) (NumberHelper.getDouble(rowHoursPerDay) * 60)));
      calendar.setCalendarMinutesPerWeek(Integer.valueOf((int) (NumberHelper.getDouble(rowHoursPerWeek) * 60)));
      calendar.setCalendarMinutesPerMonth(Integer.valueOf((int) (NumberHelper.getDouble(rowHoursPerMonth) * 60)));
      calendar.setCalendarMinutesPerYear(Integer.valueOf((int) (NumberHelper.getDouble(rowHoursPerYear) * 60)));

      //
      // If we're missing any of these figures, generate them.
      // Note that P6 allows users to enter arbitrary hours per period,
      // as far as I can see they aren't validated to see if they make sense,
      // so the figures here won't necessarily match what you'd see in P6.
      //
      if (rowHoursPerDay == null || rowHoursPerWeek == null || rowHoursPerMonth == null || rowHoursPerYear == null)
      {
         int minutesPerWeek = 0;
         int workingDays = 0;

         for (Day day : Day.values())
         {
            ProjectCalendarHours hours = calendar.getCalendarHours(day);
            if (hours == null)
            {
               continue;
            }

            if (hours.size() > 0)
            {
               ++workingDays;
               for (DateRange range : hours)
               {
                  long milliseconds = range.getEnd().getTime() - range.getStart().getTime();
                  minutesPerWeek += (milliseconds / (1000 * 60));
               }
            }
         }

         int minutesPerDay = minutesPerWeek / workingDays;
         int minutesPerMonth = minutesPerWeek * 4;
         int minutesPerYear = minutesPerMonth * 12;

         if (rowHoursPerDay == null)
         {
            calendar.setCalendarMinutesPerDay(Integer.valueOf(minutesPerDay));
         }

         if (rowHoursPerWeek == null)
         {
            calendar.setCalendarMinutesPerWeek(Integer.valueOf(minutesPerWeek));
         }

         if (rowHoursPerMonth == null)
         {
            calendar.setCalendarMinutesPerMonth(Integer.valueOf(minutesPerMonth));
         }

         if (rowHoursPerYear == null)
         {
            calendar.setCalendarMinutesPerYear(Integer.valueOf(minutesPerYear));
         }
      }

      m_eventManager.fireCalendarReadEvent(calendar);

      return calendar;
   }

   private Double getHoursPerPeriod(Row row, String name)
   {
      try
      {
         return row.getDouble(name);
      }

      catch (ClassCastException ex)
      {
         // We have seen examples of malformed calendar data where fields have been missing
         // from the record. We'll typically get a class cast exception here as we're trying
         // to process something which isn't a double.
         return null;
      }
   }

   /**
    * Process calendar days of the week.
    *
    * @param calendar project calendar
    * @param daysOfWeek calendar data
    */
   private void processCalendarDays(ProjectCalendar calendar, StructuredTextRecord daysOfWeek)
   {
      Map<Day, StructuredTextRecord> days = daysOfWeek.getChildren().stream().filter(d -> Day.getInstance(Integer.parseInt(d.getRecordName())) != null).collect(Collectors.toMap(d -> Day.getInstance(Integer.parseInt(d.getRecordName())), d -> d));

      for (Day day : Day.values())
      {
         StructuredTextRecord dayRecord = days.get(day);
         processCalendarHours(day, calendar, dayRecord == null ? StructuredTextRecord.EMPTY : dayRecord);
      }
   }

   /**
    * Process hours in a working day.
    *
    * @param day day to process
    * @param calendar project calendar
    * @param dayRecord working day data
    */
   private void processCalendarHours(Day day, ProjectCalendar calendar, StructuredTextRecord dayRecord)
   {
      // Get hours
      ProjectCalendarHours hours = calendar.addCalendarHours(day);
      List<StructuredTextRecord> recHours = dayRecord.getChildren();
      if (recHours.size() == 0)
      {
         // No data -> not working
         calendar.setWorkingDay(day, false);
      }
      else
      {
         calendar.setWorkingDay(day, true);
         // Read hours
         for (StructuredTextRecord recWorkingHours : recHours)
         {
            addHours(hours, recWorkingHours);
         }
      }
   }

   /**
    * Parses a record containing hours and add them to a container.
    *
    * @param ranges hours container
    * @param hoursRecord hours record
    */
   private void addHours(ProjectCalendarHours ranges, StructuredTextRecord hoursRecord)
   {
      String startText = hoursRecord.getAttribute("s");
      String endText = hoursRecord.getAttribute("f");

      // Ignore incomplete records
      if (startText == null || endText == null || startText.isEmpty() || endText.isEmpty())
      {
         return;
      }

      // for end time treat midnight as midnight next day
      if (endText.equals("00:00"))
      {
         endText = "24:00";
      }

      try
      {
         Date start = m_calendarTimeFormat.parse(startText);
         Date end = m_calendarTimeFormat.parse(endText);
         ranges.add(new DateRange(start, end));
      }

      catch (ParseException e)
      {
         // silently ignore date parse exceptions
      }
   }

   /**
    * Process calendar exceptions.
    *
    * @param calendar project calendar
    * @param exceptions calendar data
    */
   private void processCalendarExceptions(ProjectCalendar calendar, StructuredTextRecord exceptions)
   {
      for (StructuredTextRecord exception : exceptions.getChildren())
      {
         long daysFromEpoch = Integer.parseInt(exception.getAttribute("d"));
         Date startEx = DateHelper.getDateFromLong(EXCEPTION_EPOCH + (daysFromEpoch * DateHelper.MS_PER_DAY));

         ProjectCalendarException pce = calendar.addCalendarException(startEx, startEx);
         for (StructuredTextRecord exceptionHours : exception.getChildren())
         {
            addHours(pce, exceptionHours);
         }
      }
   }

   /**
    * Process resources.
    *
    * @param rows resource data
    */
   public void processResources(List<Row> rows)
   {
      for (Row row : rows)
      {
         Resource resource = m_project.addResource();
         processFields(m_resourceFields, row, resource);
         resource.setCalendar(m_project.getCalendars().getByUniqueID(row.getInteger("clndr_id")));

         // Even though we're not filling in a rate, filling in a time unit can still be useful
         // so that we know what rate time unit was originally used in Primavera.
         TimeUnit timeUnit = TIME_UNIT_MAP.get(row.getString("cost_qty_type"));
         resource.setStandardRateUnits(timeUnit);
         resource.setOvertimeRateUnits(timeUnit);

         // Add User Defined Fields
         populateUserDefinedFieldValues("RSRC", FieldTypeClass.RESOURCE, resource, resource.getUniqueID());

         resource.setNotesObject(getNotes(resource.getNotes()));

         m_eventManager.fireResourceReadEvent(resource);
      }
   }

   /**
    * Process roles.
    *
    * @param rows resource data
    */
   public void processRoles(List<Row> rows)
   {
      for (Row row : rows)
      {
         Resource resource = m_project.addResource();
         processFields(m_roleFields, row, resource);
         resource.setRole(Boolean.TRUE);
         resource.setUniqueID(m_roleClashMap.addID(resource.getUniqueID()));
         resource.setNotesObject(getNotes(resource.getNotes()));
      }
   }

   private Notes getNotes(String text)
   {
      Notes notes = getHtmlNote(text);
      return notes == null || notes.isEmpty() ? null : notes;
   }

   /**
    * Process resource rates.
    *
    * @param rows resource rate data
    */
   public void processResourceRates(List<Row> rows)
   {
      // Primavera defines resource cost tables by start dates so sort and define end by next
      rows.sort((r1, r2) -> {
         Integer id1 = r1.getInteger("rsrc_id");
         Integer id2 = r2.getInteger("rsrc_id");
         int cmp = NumberHelper.compare(id1, id2);
         if (cmp != 0)
         {
            return cmp;
         }
         Date d1 = r1.getDate("start_date");
         Date d2 = r2.getDate("start_date");
         return DateHelper.compare(d1, d2);
      });

      for (int i = 0; i < rows.size(); ++i)
      {
         Row row = rows.get(i);

         Integer resourceID = row.getInteger("rsrc_id");
         Rate standardRate = new Rate(row.getDouble("cost_per_qty"), TimeUnit.HOURS);
         TimeUnit standardRateFormat = TimeUnit.HOURS;
         Rate overtimeRate = new Rate(0, TimeUnit.HOURS); // does this exist in Primavera?
         TimeUnit overtimeRateFormat = TimeUnit.HOURS;
         Double costPerUse = NumberHelper.getDouble(0.0);
         Double maxUnits = NumberHelper.getDouble(NumberHelper.getDouble(row.getDouble("max_qty_per_hr")) * 100); // adjust to be % as in MS Project
         Date startDate = row.getDate("start_date");
         Date endDate = DateHelper.END_DATE_NA;

         if (i + 1 < rows.size())
         {
            Row nextRow = rows.get(i + 1);
            int nextResourceID = nextRow.getInt("rsrc_id");
            if (resourceID.intValue() == nextResourceID)
            {
               Calendar cal = DateHelper.popCalendar(nextRow.getDate("start_date"));
               cal.add(Calendar.MINUTE, -1);
               endDate = cal.getTime();
               DateHelper.pushCalendar(cal);
            }
         }

         Resource resource = m_project.getResourceByUniqueID(resourceID);
         if (resource != null)
         {
            if (startDate == null || startDate.getTime() < DateHelper.START_DATE_NA.getTime())
            {
               startDate = DateHelper.START_DATE_NA;
            }

            if (endDate == null || endDate.getTime() > DateHelper.END_DATE_NA.getTime())
            {
               endDate = DateHelper.END_DATE_NA;
            }

            CostRateTable costRateTable = resource.getCostRateTable(0);
            if (costRateTable == null)
            {
               costRateTable = new CostRateTable();
               resource.setCostRateTable(0, costRateTable);
            }
            CostRateTableEntry entry = new CostRateTableEntry(standardRate, standardRateFormat, overtimeRate, overtimeRateFormat, costPerUse, startDate, endDate);
            costRateTable.add(entry);

            resource.getAvailability().add(new Availability(startDate, endDate, maxUnits));
         }
      }
   }

   /**
    * Process role rates.
    *
    * @param rows role rate data
    */
   public void processRoleRates(List<Row> rows)
   {
      // Primavera defines resource cost tables by start dates so sort and define end by next
      rows.sort((r1, r2) -> {
         Integer id1 = r1.getInteger("role_id");
         Integer id2 = r2.getInteger("role_id");
         int cmp = NumberHelper.compare(id1, id2);
         if (cmp != 0)
         {
            return cmp;
         }
         Date d1 = r1.getDate("start_date");
         Date d2 = r2.getDate("start_date");
         return DateHelper.compare(d1, d2);
      });

      for (int i = 0; i < rows.size(); ++i)
      {
         Row row = rows.get(i);

         Rate standardRate = new Rate(row.getDouble("cost_per_qty"), TimeUnit.HOURS);
         TimeUnit standardRateFormat = TimeUnit.HOURS;
         Rate overtimeRate = new Rate(0, TimeUnit.HOURS); // does this exist in Primavera?
         TimeUnit overtimeRateFormat = TimeUnit.HOURS;
         Double costPerUse = NumberHelper.getDouble(0.0);
         Double maxUnits = NumberHelper.getDouble(NumberHelper.getDouble(row.getDouble("max_qty_per_hr")) * 100); // adjust to be % as in MS Project
         Date startDate = row.getDate("start_date");
         Date endDate = DateHelper.END_DATE_NA;

         if (i + 1 < rows.size())
         {
            Row nextRow = rows.get(i + 1);
            if (NumberHelper.equals(row.getInteger("role_id"), nextRow.getInteger("role_id")))
            {
               Calendar cal = DateHelper.popCalendar(nextRow.getDate("start_date"));
               cal.add(Calendar.MINUTE, -1);
               endDate = cal.getTime();
               DateHelper.pushCalendar(cal);
            }
         }

         Resource resource = m_project.getResourceByUniqueID(m_roleClashMap.getID(row.getInteger("role_id")));
         if (resource != null)
         {
            if (startDate == null || startDate.getTime() < DateHelper.START_DATE_NA.getTime())
            {
               startDate = DateHelper.START_DATE_NA;
            }

            if (endDate == null || endDate.getTime() > DateHelper.END_DATE_NA.getTime())
            {
               endDate = DateHelper.END_DATE_NA;
            }

            CostRateTable costRateTable = resource.getCostRateTable(0);
            if (costRateTable == null)
            {
               costRateTable = new CostRateTable();
               resource.setCostRateTable(0, costRateTable);
            }
            CostRateTableEntry entry = new CostRateTableEntry(standardRate, standardRateFormat, overtimeRate, overtimeRateFormat, costPerUse, startDate, endDate);
            costRateTable.add(entry);

            resource.getAvailability().add(new Availability(startDate, endDate, maxUnits));
         }
      }
   }

   /**
    * Process tasks.
    *
    * @param wbs WBS task data
    * @param tasks task data
    * @param wbsNotes WBS note data
    * @param taskNotes task note data
    */
   public void processTasks(List<Row> wbs, List<Row> tasks, Map<Integer, Notes> wbsNotes, Map<Integer, Notes> taskNotes)
   {
      ProjectProperties projectProperties = m_project.getProjectProperties();
      String projectName = projectProperties.getName();
      Set<Task> wbsTasks = new HashSet<>();
      boolean baselineFromCurrentProject = m_project.getProjectProperties().getBaselineProjectUniqueID() == null;

      //
      // We set the project name when we read the project properties, but that's just
      // the short name. The full project name lives on the first WBS item. Rather than
      // querying twice, we'll just set it here where we have access to the WBS items.
      // We'll leave the short name in place if there is no WBS.
      //
      if (!wbs.isEmpty())
      {
         projectProperties.setName(wbs.get(0).getString("wbs_name"));
      }

      //
      // Read WBS entries and create tasks.
      // Note that the wbs list is supplied to us in the correct order.
      //
      for (Row row : wbs)
      {
         Task task = m_project.addTask();
         task.setProject(projectName); // P6 task always belongs to project
         task.setSummary(true);
         processFields(m_wbsFields, row, task);
         populateUserDefinedFieldValues("PROJWBS", FieldTypeClass.TASK, task, task.getUniqueID());
         task.setNotesObject(wbsNotes.get(task.getUniqueID()));
         // WBS entries will be critical if any child activities are critical.
         // Set an explicit value here to deal with WBS entries without child activities.
         // If we don't do this, the logic in Task.getCritical will mark WBS entries without
         // child activities as critical.
         task.setCritical(false);
         m_activityClashMap.addID(task.getUniqueID());
         wbsTasks.add(task);
         m_eventManager.fireTaskReadEvent(task);
      }

      //
      // Create hierarchical structure
      //
      m_project.getChildTasks().clear();
      for (Row row : wbs)
      {
         Task task = m_project.getTaskByUniqueID(row.getInteger("wbs_id"));
         Task parentTask = m_project.getTaskByUniqueID(row.getInteger("parent_wbs_id"));
         if (parentTask == null)
         {
            m_project.getChildTasks().add(task);
         }
         else
         {
            m_project.getChildTasks().remove(task);
            parentTask.getChildTasks().add(task);

            if (m_wbsIsFullPath)
            {
               task.setWBS(parentTask.getWBS() + DEFAULT_WBS_SEPARATOR + task.getWBS());
            }
         }

         task.setActivityID(task.getWBS());
      }

      //
      // Read Task entries and create tasks
      //

      // If the schedule is using longest path to determine critical activities
      // we currently don't have enough information to correctly set this attribute.
      // In this case we'll force the critical flag to false to avoid activities
      // being incorrectly marked as critical.
      boolean forceCriticalToFalse = projectProperties.getCriticalActivityType() == CriticalActivityType.LONGEST_PATH;

      for (Row row : tasks)
      {
         Task task;
         Integer parentTaskID = row.getInteger("wbs_id");
         Task parentTask = m_project.getTaskByUniqueID(parentTaskID);
         if (parentTask == null)
         {
            task = m_project.addTask();
         }
         else
         {
            task = parentTask.addTask();
         }
         task.setProject(projectName); // P6 task always belongs to project

         processFields(m_taskFields, row, task);

         task.setMilestone(BooleanHelper.getBoolean(MILESTONE_MAP.get(row.getString("task_type"))));
         task.setActivityStatus(STATUS_MAP.get(row.getString("status_code")));
         task.setActivityType(ACTIVITY_TYPE_MAP.get(row.getString("task_type")));

         // Only "Resource Dependent" activities consider resource calendars during scheduling in P6.
         task.setIgnoreResourceCalendar(!"TT_Rsrc".equals(row.getString("task_type")));

         task.setPercentCompleteType(PERCENT_COMPLETE_TYPE.get(row.getString("complete_pct_type")));
         task.setPercentageWorkComplete(calculateUnitsPercentComplete(row));
         task.setPercentageComplete(calculateDurationPercentComplete(row));
         task.setPhysicalPercentComplete(calculatePhysicalPercentComplete(row));

         if (m_matchPrimaveraWBS && parentTask != null)
         {
            task.setWBS(parentTask.getWBS());
         }

         Integer uniqueID = task.getUniqueID();

         // Add User Defined Fields - before we handle ID clashes
         populateUserDefinedFieldValues("TASK", FieldTypeClass.TASK, task, uniqueID);

         populateActivityCodes(task);

         task.setNotesObject(taskNotes.get(uniqueID));

         task.setUniqueID(m_activityClashMap.addID(uniqueID));

         Integer calId = row.getInteger("clndr_id");
         ProjectCalendar cal = m_project.getCalendarByUniqueID(calId);
         task.setCalendar(cal);

         populateField(task, TaskField.START, TaskField.ACTUAL_START, TaskField.REMAINING_EARLY_START, TaskField.PLANNED_START);
         populateField(task, TaskField.FINISH, TaskField.ACTUAL_FINISH, TaskField.REMAINING_EARLY_FINISH, TaskField.PLANNED_FINISH);
         Duration work = Duration.add(task.getActualWork(), task.getRemainingWork(), task.getEffectiveCalendar());
         task.setWork(work);

         // Calculate actual duration
         Date actualStart = task.getActualStart();
         if (actualStart != null)
         {
            Date finish = task.getActualFinish();
            if (finish == null)
            {
               finish = m_project.getProjectProperties().getStatusDate();

               // Handle the case where the actual start is after the status date
               if (finish != null && finish.getTime() < actualStart.getTime())
               {
                  finish = actualStart;
               }
            }

            if (finish != null)
            {
               cal = task.getEffectiveCalendar();
               task.setActualDuration(cal.getWork(actualStart, finish, TimeUnit.HOURS));
            }
         }

         // Calculate duration at completion
         Duration durationAtCompletion = Duration.add(task.getActualDuration(), task.getRemainingDuration(), task.getEffectiveCalendar());
         task.setDuration(durationAtCompletion);

         // Force calculation here to avoid later issues
         task.getStartSlack();
         task.getFinishSlack();

         if (forceCriticalToFalse)
         {
            task.setCritical(false);
         }
         else
         {
            task.getCritical();
         }

         if (baselineFromCurrentProject)
         {
            populateBaselineFromCurrentProject(task);
         }

         m_eventManager.fireTaskReadEvent(task);
      }

      new ActivitySorter(wbsTasks).sort(m_project);

      updateStructure();
   }

   private void populateBaselineFromCurrentProject(Task task)
   {
      task.setBaselineCost(task.getPlannedCost());
      task.setBaselineDuration(task.getPlannedDuration());
      task.setBaselineFinish(task.getPlannedFinish());
      task.setBaselineStart(task.getPlannedStart());
      task.setBaselineWork(task.getPlannedWork());
   }

   /**
    * Read details of any activity codes assigned to this task.
    *
    * @param task parent task
    */
   private void populateActivityCodes(Task task)
   {
      List<Integer> list = m_activityCodeAssignments.get(task.getUniqueID());
      if (list != null)
      {
         for (Integer id : list)
         {
            ActivityCodeValue value = m_activityCodeMap.get(id);
            if (value != null)
            {
               task.addActivityCode(value);
            }
         }
      }
   }

   /**
    * Allocate a UDF to one of the available custom fields.
    *
    * @param fieldTypeClass field type
    * @param dataType field data type
    * @return FieldType instance for allocated field
    */
   private FieldType allocateUserDefinedField(FieldTypeClass fieldTypeClass, UserFieldDataType dataType)
   {
      FieldType fieldType = null;

      try
      {
         switch (fieldTypeClass)
         {
            case TASK:
            {
               do
               {
                  fieldType = m_taskUdfCounters.nextField(TaskField.class, dataType);
               }
               while (m_taskFields.containsKey(fieldType) || m_wbsFields.containsKey(fieldType));
               break;
            }

            case RESOURCE:
            {
               do
               {
                  fieldType = m_resourceUdfCounters.nextField(ResourceField.class, dataType);
               }
               while (m_resourceFields.containsKey(fieldType));
               break;
            }

            case ASSIGNMENT:
            {
               do
               {
                  fieldType = m_assignmentUdfCounters.nextField(AssignmentField.class, dataType);
               }
               while (m_assignmentFields.containsKey(fieldType));
               break;
            }

            default:
            {
               break;
            }
         }
      }

      catch (Exception ex)
      {
         //
         // SF#227: If we get an exception thrown here... it's likely that
         // we've run out of user defined fields, for example
         // there are only 30 TEXT fields. We'll ignore this: the user
         // defined field won't be mapped to an alias, so we'll
         // ignore it when we read in the values.
         //
      }

      return fieldType;
   }

   /**
    * Adds a user defined field value to a task.
    *
    * @param fieldType field type
    * @param container FieldContainer instance
    * @param row UDF data
    */
   private void addUDFValue(FieldTypeClass fieldType, FieldContainer container, Row row)
   {
      Integer fieldId = row.getInteger("udf_type_id");
      FieldType field = m_udfFields.get(fieldId);

      Object value;
      if (field != null)
      {
         DataType fieldDataType = field.getDataType();

         switch (fieldDataType)
         {
            case DATE:
            {
               value = row.getDate("udf_date");
               break;
            }

            case CURRENCY:
            case NUMERIC:
            {
               value = row.getDouble("udf_number");
               break;
            }

            case GUID:
            case INTEGER:
            {
               value = row.getInteger("udf_code_id");
               break;
            }

            case BOOLEAN:
            {
               String text = row.getString("udf_text");
               if (text != null)
               {
                  // before a normal boolean parse, we try to lookup the text as a P6 static type indicator UDF
                  value = STATICTYPE_UDF_MAP.get(text);
                  if (value == null)
                  {
                     value = Boolean.valueOf(row.getBoolean("udf_text"));
                  }
               }
               else
               {
                  value = Boolean.valueOf(row.getBoolean("udf_number"));
               }
               break;
            }

            default:
            {
               value = row.getString("udf_text");
               break;
            }
         }

         container.set(field, value);
      }
   }

   /**
    * Populate the UDF values for this entity.
    *
    * @param tableName parent table name
    * @param type entity type
    * @param container entity
    * @param uniqueID entity Unique ID
    */
   private void populateUserDefinedFieldValues(String tableName, FieldTypeClass type, FieldContainer container, Integer uniqueID)
   {
      Map<Integer, List<Row>> tableData = m_udfValues.get(tableName);
      if (tableData != null)
      {
         List<Row> udf = tableData.get(uniqueID);
         if (udf != null)
         {
            for (Row r : udf)
            {
               addUDFValue(type, container, r);
            }
         }
      }
   }

   /**
    * Create a map of notebook topics.
    *
    * @param rows notebook topic rows
    * @return notebook topic map
    */
   public Map<Integer, String> getNotebookTopics(List<Row> rows)
   {
      Map<Integer, String> topics = new HashMap<>();
      rows.forEach(row -> topics.put(row.getInteger("memo_type_id"), row.getString("memo_type")));
      return topics;
   }

   /**
    * Convert the P6 notes to plain text.
    *
    * @param topics topic map
    * @param rows notebook rows
    * @param idColumn id column name
    * @param textColumn text column name
    * @return note text
    */
   public Map<Integer, Notes> getNotes(Map<Integer, String> topics, List<Row> rows, String idColumn, String textColumn)
   {
      Map<Integer, Map<Integer, List<String>>> map = rows.stream().collect(Collectors.groupingBy(r -> r.getInteger(idColumn), Collectors.groupingBy(r -> r.getInteger("memo_type_id"), Collectors.mapping(r -> r.getString(textColumn), Collectors.toList()))));

      Map<Integer, Notes> result = new HashMap<>();

      for (Map.Entry<Integer, Map<Integer, List<String>>> entry : map.entrySet())
      {
         List<Notes> list = new ArrayList<>();
         for (Map.Entry<Integer, List<String>> topicEntry : entry.getValue().entrySet())
         {
            topicEntry.getValue().stream().map(this::getHtmlNote).filter(n -> n != null && !n.isEmpty()).forEach(n -> list.add(new StructuredNotes(topicEntry.getKey(), topics.get(topicEntry.getKey()), n)));
         }
         result.put(entry.getKey(), new ParentNotes(list));
      }

      return result;
   }

   /**
    * Create an HtmlNote instance.
    *
    * @param text note text
    * @return HtmlNote instance
    */
   private HtmlNotes getHtmlNote(String text)
   {
      if (text == null)
      {
         return null;
      }

      // Remove BOM and NUL characters
      String html = text.replaceAll("[\\uFEFF\\uFFFE\\x00]", "");

      // Replace newlines
      html = html.replaceAll("\\x7F\\x7F", "\n");

      HtmlNotes result = new HtmlNotes(html);

      return result.isEmpty() ? null : result;
   }

   /**
    * Populates a field based on planned and actual values.
    *
    * @param container field container
    * @param target target field
    * @param types fields to test for not-null values
    */
   private void populateField(FieldContainer container, FieldType target, FieldType... types)
   {
      for (FieldType type : types)
      {
         Object value = container.getCachedValue(type);
         if (value != null)
         {
            container.set(target, value);
            break;
         }
      }
   }

   /**
    * Iterates through the tasks setting the correct
    * outline level and ID values.
    */
   private void updateStructure()
   {
      int id = 1;
      Integer outlineLevel = Integer.valueOf(1);
      for (Task task : m_project.getChildTasks())
      {
         id = updateStructure(id, task, outlineLevel);
      }
   }

   /**
    * Iterates through the tasks setting the correct
    * outline level and ID values.
    *
    * @param id current ID value
    * @param task current task
    * @param outlineLevel current outline level
    * @return next ID value
    */
   private int updateStructure(int id, Task task, Integer outlineLevel)
   {
      task.setID(Integer.valueOf(id++));
      task.setOutlineLevel(outlineLevel);
      outlineLevel = Integer.valueOf(outlineLevel.intValue() + 1);
      for (Task childTask : task.getChildTasks())
      {
         id = updateStructure(id, childTask, outlineLevel);
      }
      return id;
   }

   /**
    * This method sets the calendar used by a WBS entry. In P6 if all activities
    * under a WBS entry use the same calendar, the WBS entry uses this calendar
    * for date calculation. If the activities use different calendars, the WBS
    * entry will use the project's default calendar.
    *
    * @param task task to validate
    * @return calendar used by this task
    */
   private ProjectCalendar rollupCalendars(Task task)
   {
      ProjectCalendar result = null;

      if (task.hasChildTasks())
      {
         List<ProjectCalendar> calendars = task.getChildTasks().stream().map(this::rollupCalendars).distinct().collect(Collectors.toList());

         if (calendars.size() == 1)
         {
            ProjectCalendar firstCalendar = calendars.get(0);
            if (firstCalendar != null && firstCalendar != m_project.getDefaultCalendar())
            {
               result = firstCalendar;
               task.setCalendar(result);
            }
         }
      }
      else
      {
         result = task.getCalendar();
      }

      return result;
   }

   /**
    * The Primavera WBS entries we read in as tasks have user-entered start and end dates
    * which aren't calculated or adjusted based on the child task dates. We try
    * to compensate for this by using these user-entered dates as baseline dates, and
    * deriving the planned start, actual start, planned finish and actual finish from
    * the child tasks. This method recursively descends through the tasks to do this.
    *
    * @param parentTask parent task.
    */
   private void rollupDates(Task parentTask)
   {
      if (parentTask.hasChildTasks())
      {
         int finished = 0;
         Date startDate = parentTask.getStart();
         Date finishDate = parentTask.getFinish();
         Date plannedStartDate = parentTask.getPlannedStart();
         Date plannedFinishDate = parentTask.getPlannedFinish();
         Date actualStartDate = parentTask.getActualStart();
         Date actualFinishDate = parentTask.getActualFinish();
         Date earlyStartDate = parentTask.getEarlyStart();
         Date earlyFinishDate = parentTask.getEarlyFinish();
         Date lateStartDate = parentTask.getLateStart();
         Date lateFinishDate = parentTask.getLateFinish();
         Date baselineStartDate = parentTask.getBaselineStart();
         Date baselineFinishDate = parentTask.getBaselineFinish();
         Date remainingEarlyStartDate = parentTask.getRemainingEarlyStart();
         Date remainingEarlyFinishDate = parentTask.getRemainingEarlyFinish();
         Date remainingLateStartDate = parentTask.getRemainingLateStart();
         Date remainingLateFinishDate = parentTask.getRemainingLateFinish();
         boolean critical = false;

         for (Task task : parentTask.getChildTasks())
         {
            rollupDates(task);

            // the child tasks can have null dates (e.g. for nested wbs elements with no task children) so we
            // still must protect against some children having null dates

            startDate = DateHelper.min(startDate, task.getStart());
            finishDate = DateHelper.max(finishDate, task.getFinish());
            plannedStartDate = DateHelper.min(plannedStartDate, task.getPlannedStart());
            plannedFinishDate = DateHelper.max(plannedFinishDate, task.getPlannedFinish());
            actualStartDate = DateHelper.min(actualStartDate, task.getActualStart());
            actualFinishDate = DateHelper.max(actualFinishDate, task.getActualFinish());
            earlyStartDate = DateHelper.min(earlyStartDate, task.getEarlyStart());
            earlyFinishDate = DateHelper.max(earlyFinishDate, task.getEarlyFinish());
            remainingEarlyStartDate = DateHelper.min(remainingEarlyStartDate, task.getRemainingEarlyStart());
            remainingEarlyFinishDate = DateHelper.max(remainingEarlyFinishDate, task.getRemainingEarlyFinish());
            lateStartDate = DateHelper.min(lateStartDate, task.getLateStart());
            lateFinishDate = DateHelper.max(lateFinishDate, task.getLateFinish());
            remainingLateStartDate = DateHelper.min(remainingLateStartDate, task.getRemainingLateStart());
            remainingLateFinishDate = DateHelper.max(remainingLateFinishDate, task.getRemainingLateFinish());
            baselineStartDate = DateHelper.min(baselineStartDate, task.getBaselineStart());
            baselineFinishDate = DateHelper.max(baselineFinishDate, task.getBaselineFinish());

            if (task.getActualFinish() != null)
            {
               ++finished;
            }

            critical = critical || task.getCritical();
         }

         parentTask.setStart(startDate);
         parentTask.setFinish(finishDate);
         parentTask.setPlannedStart(plannedStartDate);
         parentTask.setPlannedFinish(plannedFinishDate);
         parentTask.setActualStart(actualStartDate);
         parentTask.setEarlyStart(earlyStartDate);
         parentTask.setEarlyFinish(earlyFinishDate);
         parentTask.setRemainingEarlyStart(remainingEarlyStartDate);
         parentTask.setRemainingEarlyFinish(remainingEarlyFinishDate);
         parentTask.setLateStart(lateStartDate);
         parentTask.setLateFinish(lateFinishDate);
         parentTask.setRemainingLateStart(remainingLateStartDate);
         parentTask.setRemainingLateFinish(remainingLateFinishDate);
         parentTask.setBaselineStart(baselineStartDate);
         parentTask.setBaselineFinish(baselineFinishDate);

         //
         // Only if all child tasks have actual finish dates do we
         // set the actual finish date on the parent task.
         //
         if (finished == parentTask.getChildTasks().size())
         {
            parentTask.setActualFinish(actualFinishDate);
         }

         Duration plannedDuration = null;
         if (plannedStartDate != null && plannedFinishDate != null)
         {
            plannedDuration = parentTask.getEffectiveCalendar().getWork(plannedStartDate, plannedFinishDate, TimeUnit.HOURS);
            parentTask.setPlannedDuration(plannedDuration);
         }

         Duration actualDuration = null;
         Duration remainingDuration = null;
         if (parentTask.getActualFinish() == null)
         {
            Date taskStartDate = parentTask.getRemainingEarlyStart();
            if (taskStartDate == null)
            {
               taskStartDate = parentTask.getEarlyStart();
               if (taskStartDate == null)
               {
                  taskStartDate = plannedStartDate;
               }
            }

            Date taskFinishDate = parentTask.getRemainingEarlyFinish();
            if (taskFinishDate == null)
            {
               taskFinishDate = parentTask.getEarlyFinish();
               if (taskFinishDate == null)
               {
                  taskFinishDate = plannedFinishDate;
               }
            }

            if (taskStartDate != null)
            {
               if (parentTask.getActualStart() != null)
               {
                  actualDuration = parentTask.getEffectiveCalendar().getWork(parentTask.getActualStart(), taskStartDate, TimeUnit.HOURS);
               }

               if (taskFinishDate != null)
               {
                  remainingDuration = parentTask.getEffectiveCalendar().getWork(taskStartDate, taskFinishDate, TimeUnit.HOURS);
               }
            }
         }
         else
         {
            actualDuration = parentTask.getEffectiveCalendar().getWork(parentTask.getActualStart(), parentTask.getActualFinish(), TimeUnit.HOURS);
            remainingDuration = Duration.getInstance(0, TimeUnit.HOURS);
         }

         if (actualDuration != null && actualDuration.getDuration() < 0)
         {
            actualDuration = null;
         }

         if (remainingDuration != null && remainingDuration.getDuration() < 0)
         {
            remainingDuration = null;
         }

         parentTask.setActualDuration(actualDuration);
         parentTask.setRemainingDuration(remainingDuration);
         parentTask.setDuration(Duration.add(actualDuration, remainingDuration, parentTask.getEffectiveCalendar()));

         if (plannedDuration != null && remainingDuration != null && plannedDuration.getDuration() != 0)
         {
            double durationPercentComplete = ((plannedDuration.getDuration() - remainingDuration.getDuration()) / plannedDuration.getDuration()) * 100.0;
            if (durationPercentComplete < 0)
            {
               durationPercentComplete = 0;
            }
            else
            {
               if (durationPercentComplete > 100)
               {
                  durationPercentComplete = 100;
               }
            }
            parentTask.setPercentageComplete(Double.valueOf(durationPercentComplete));
            parentTask.setPercentCompleteType(PercentCompleteType.DURATION);
         }

         // Force calculation here to avoid later issues
         parentTask.getStartSlack();
         parentTask.getFinishSlack();
         parentTask.setCritical(critical);
      }
   }

   /**
    * The Primavera WBS entries we read in as tasks don't have work entered. We try
    * to compensate for this by summing the child tasks' work. This method recursively
    * descends through the tasks to do this.
    *
    * @param parentTask parent task.
    */
   private void rollupWork(Task parentTask)
   {
      if (parentTask.hasChildTasks())
      {
         ProjectCalendar calendar = parentTask.getEffectiveCalendar();

         Duration actualWork = null;
         Duration plannedWork = null;
         Duration remainingWork = null;
         Duration work = null;

         for (Task task : parentTask.getChildTasks())
         {
            rollupWork(task);

            actualWork = Duration.add(actualWork, task.getActualWork(), calendar);
            plannedWork = Duration.add(plannedWork, task.getPlannedWork(), calendar);
            remainingWork = Duration.add(remainingWork, task.getRemainingWork(), calendar);
            work = Duration.add(work, task.getWork(), calendar);
         }

         parentTask.setActualWork(actualWork);
         parentTask.setPlannedWork(plannedWork);
         parentTask.setRemainingWork(remainingWork);
         parentTask.setWork(work);
      }
   }

   /**
    * Processes predecessor data.
    *
    * @param rows predecessor data
    */
   public void processPredecessors(List<Row> rows)
   {
      for (Row row : rows)
      {
         Integer successorID = m_activityClashMap.getID(row.getInteger("task_id"));
         Integer predecessorID = m_activityClashMap.getID(row.getInteger("pred_task_id"));

         Task successorTask = m_project.getTaskByUniqueID(successorID);
         Task predecessorTask = m_project.getTaskByUniqueID(predecessorID);

         RelationType type = getRelationType(row.getString("pred_type"));
         Duration lag = row.getDuration("lag_hr_cnt");

         if (successorTask != null && predecessorTask != null)
         {
            Relation relation = successorTask.addPredecessor(predecessorTask, type, lag);
            relation.setUniqueID(row.getInteger("task_pred_id"));
            m_eventManager.fireRelationReadEvent(relation);
         }
         else
         {
            // If we're missing the predecessor or successor we assume they are external relations
            if (successorTask != null && predecessorTask == null)
            {
               ExternalRelation relation = new ExternalRelation(predecessorID, successorTask, type, lag, true);
               m_externalRelations.add(relation);
               relation.setUniqueID(row.getInteger("task_pred_id"));
            }
            else
            {
               if (successorTask == null && predecessorTask != null)
               {
                  ExternalRelation relation = new ExternalRelation(successorID, predecessorTask, type, lag, false);
                  m_externalRelations.add(relation);
                  relation.setUniqueID(row.getInteger("task_pred_id"));
               }
            }
         }
      }
   }

   /**
    * Look up the relation type between tasks.
    *
    * @param value string representation of a relation type
    * @return RelationType instance
    */
   private RelationType getRelationType(String value)
   {
      RelationType result = null;
      if (value != null)
      {
         // We have examples from XER files where the relation type is in the form
         // PR_FF1, PR_FF2 and so on. We'll try to handle this by stripping off any
         // suffix to determine the original relation type.
         if (value.length() > 5)
         {
            value = value.substring(0, 5);
         }
         result = RELATION_TYPE_MAP.get(value);
      }

      // Default to Finish-Start if we can't determine the type
      return result == null ? RelationType.FINISH_START : result;
   }

   /**
    * Process assignment data.
    *
    * @param rows assignment data
    * @param workContours work contours
    */
   public void processAssignments(List<Row> rows, Map<Integer, WorkContour> workContours)
   {
      for (Row row : rows)
      {
         Task task = m_project.getTaskByUniqueID(m_activityClashMap.getID(row.getInteger("task_id")));
         Integer resourceID = row.getInteger("rsrc_id") == null ? m_roleClashMap.getID(row.getInteger("role_id")) : row.getInteger("rsrc_id");
         Resource resource = m_project.getResourceByUniqueID(resourceID);
         if (task != null && resource != null)
         {
            ResourceAssignment assignment = task.addResourceAssignment(resource);
            processFields(m_assignmentFields, row, assignment);

            populateField(assignment, AssignmentField.START, AssignmentField.ACTUAL_START, AssignmentField.PLANNED_START);
            populateField(assignment, AssignmentField.FINISH, AssignmentField.ACTUAL_FINISH, AssignmentField.PLANNED_FINISH);

            // include actual overtime work in work calculations
            Duration remainingWork = row.getDuration("remain_qty");
            Duration actualOvertimeWork = row.getDuration("act_ot_qty");
            Duration actualRegularWork = row.getDuration("act_reg_qty");
            Duration actualWork = Duration.add(actualOvertimeWork, actualRegularWork, task.getEffectiveCalendar());
            Duration totalWork = Duration.add(actualWork, remainingWork, task.getEffectiveCalendar());
            assignment.setActualWork(actualWork);
            assignment.setWork(totalWork);
            assignment.setWorkContour(workContours.get(row.getInteger("curv_id")));

            // include actual overtime cost in cost calculations
            assignment.setActualCost(NumberHelper.sumAsDouble(row.getDouble("act_reg_cost"), row.getDouble("act_ot_cost")));
            assignment.setCost(NumberHelper.sumAsDouble(assignment.getActualCost(), assignment.getRemainingCost()));

            // roll up to parent task
            task.setPlannedCost(NumberHelper.sumAsDouble(task.getPlannedCost(), assignment.getPlannedCost()));
            task.setActualCost(NumberHelper.sumAsDouble(task.getActualCost(), assignment.getActualCost()));
            task.setRemainingCost(NumberHelper.sumAsDouble(task.getRemainingCost(), assignment.getRemainingCost()));
            task.setCost(NumberHelper.sumAsDouble(task.getCost(), assignment.getCost()));

            double units;
            if (resource.getType() == ResourceType.MATERIAL)
            {
               units = (totalWork == null) ? 0 : totalWork.getDuration() * 100;
            }
            else // RT_Labor & RT_Equip
            {
               units = NumberHelper.getDouble(row.getDouble("target_qty_per_hr")) * 100;
            }
            assignment.setUnits(NumberHelper.getDouble(units));

            // Add User Defined Fields
            populateUserDefinedFieldValues("TASKRSRC", FieldTypeClass.ASSIGNMENT, assignment, assignment.getUniqueID());

            m_eventManager.fireAssignmentReadEvent(assignment);
         }
      }
   }

   /**
    * Sets task cost fields by summing the resource assignment costs. The "projcost" table isn't
    * necessarily available in XER files so we do this instead to back into task costs. Costs for
    * the summary tasks constructed from Primavera WBS entries are calculated by recursively
    * summing child costs.
    */
   public void rollupValues()
   {
      m_project.getChildTasks().forEach(this::rollupCalendars);
      m_project.getChildTasks().forEach(this::rollupDates);
      m_project.getChildTasks().forEach(this::rollupWork);
      m_project.getChildTasks().forEach(this::rollupCosts);

      if (m_project.getProjectProperties().getBaselineProjectUniqueID() == null)
      {
         m_project.getTasks().stream().filter(Task::getSummary).forEach(this::populateBaselineFromCurrentProject);
      }
   }

   /**
    * See the notes above.
    *
    * @param parentTask parent task
    */
   private void rollupCosts(Task parentTask)
   {
      if (parentTask.hasChildTasks())
      {
         double plannedCost = 0;
         double actualCost = 0;
         double remainingCost = 0;
         double cost = 0;

         //process children first before adding their costs
         for (Task child : parentTask.getChildTasks())
         {
            rollupCosts(child);
            plannedCost += NumberHelper.getDouble(child.getPlannedCost());
            actualCost += NumberHelper.getDouble(child.getActualCost());
            remainingCost += NumberHelper.getDouble(child.getRemainingCost());
            cost += NumberHelper.getDouble(child.getCost());
         }

         parentTask.setPlannedCost(NumberHelper.getDouble(plannedCost));
         parentTask.setActualCost(NumberHelper.getDouble(actualCost));
         parentTask.setRemainingCost(NumberHelper.getDouble(remainingCost));
         parentTask.setCost(NumberHelper.getDouble(cost));
      }
   }

   /**
    * Code common to both XER and database readers to extract
    * currency format data.
    *
    * @param row row containing currency data
    */
   public void processDefaultCurrency(Row row)
   {
      ProjectProperties properties = m_project.getProjectProperties();
      properties.setCurrencySymbol(row.getString("curr_symbol"));
      properties.setSymbolPosition(CURRENCY_SYMBOL_POSITION_MAP.get(row.getString("pos_curr_fmt_type")));
      properties.setCurrencyDigits(row.getInteger("decimal_digit_cnt"));
      properties.setThousandsSeparator(row.getString("digit_group_symbol").charAt(0));
      properties.setDecimalSeparator(row.getString("decimal_symbol").charAt(0));
   }

   /**
    * Extract expense items and add to a task.
    *
    * @param rows expense item rows
    */
   public void processExpenseItems(List<Row> rows)
   {
      for (Row row : rows)
      {
         Task task = m_project.getTaskByUniqueID(row.getInteger("task_id"));
         if (task != null)
         {
            List<ExpenseItem> items = task.getExpenseItems();
            if (items == null)
            {
               items = new ArrayList<>();
               task.setExpenseItems(items);
            }

            ExpenseItem ei = new ExpenseItem(task);
            items.add(ei);

            ei.setAccount(m_project.getCostAccounts().getByUniqueID(row.getInteger("acct_id")));
            ei.setAccrueType(ACCRUE_TYPE_MAP.get(row.getString("cost_load_type")));
            ei.setActualCost(row.getDouble("act_cost"));
            ei.setAutoComputeActuals(row.getBoolean("auto_compute_act_flag"));
            ei.setCategory(m_project.getExpenseCategories().getByUniqueID(row.getInteger("cost_type_id")));
            ei.setDescription(row.getString("cost_descr"));
            ei.setDocumentNumber(row.getString("po_number"));
            ei.setName(row.getString("cost_name"));
            ei.setPlannedCost(row.getDouble("target_cost"));
            ei.setPlannedUnits(row.getDouble("target_qty"));
            ei.setPricePerUnit(row.getDouble("cost_per_qty"));
            ei.setRemainingCost(row.getDouble("remain_cost"));
            ei.setUniqueID(row.getInteger("cost_item_id"));
            ei.setUnitOfMeasure(row.getString("qty_name"));
            ei.setVendor(row.getString("vendor_name"));

            ei.setAtCompletionCost(NumberHelper.sumAsDouble(ei.getActualCost(), ei.getRemainingCost()));

            double pricePerUnit = NumberHelper.getDouble(ei.getPricePerUnit());
            if (pricePerUnit != 0.0)
            {
               ei.setActualUnits(Double.valueOf(NumberHelper.getDouble(ei.getActualCost()) / pricePerUnit));
               ei.setRemainingUnits(Double.valueOf(NumberHelper.getDouble(ei.getRemainingCost()) / pricePerUnit));
               ei.setAtCompletionUnits(NumberHelper.sumAsDouble(ei.getActualUnits(), ei.getRemainingUnits()));
            }

            // Roll up to parent task
            task.setPlannedCost(NumberHelper.sumAsDouble(task.getPlannedCost(), ei.getPlannedCost()));
            task.setActualCost(NumberHelper.sumAsDouble(task.getActualCost(), ei.getActualCost()));
            task.setRemainingCost(NumberHelper.sumAsDouble(task.getRemainingCost(), ei.getRemainingCost()));
            task.setCost(NumberHelper.sumAsDouble(task.getCost(), ei.getAtCompletionCost()));
         }
      }
   }

   /**
    * Extract schedule options.
    *
    * @param row schedule options row
    */
   public void processScheduleOptions(Row row)
   {
      Map<String, Object> customProperties = new TreeMap<>();

      //
      // Leveling Options
      //
      // Automatically level resources when scheduling
      customProperties.put("ConsiderAssignmentsInOtherProjects", Boolean.valueOf(row.getBoolean("level_outer_assign_flag")));
      customProperties.put("ConsiderAssignmentsInOtherProjectsWithPriorityEqualHigherThan", row.getString("level_outer_assign_priority"));
      customProperties.put("PreserveScheduledEarlyAndLateDates", Boolean.valueOf(row.getBoolean("level_keep_sched_date_flag")));
      // Recalculate assignment costs after leveling
      customProperties.put("LevelAllResources", Boolean.valueOf(row.getBoolean("level_all_rsrc_flag")));
      customProperties.put("LevelResourcesOnlyWithinActivityTotalFloat", Boolean.valueOf(row.getBoolean("level_within_float_flag")));
      customProperties.put("PreserveMinimumFloatWhenLeveling", row.getString("level_float_thrs_cnt"));
      customProperties.put("MaxPercentToOverallocateResources", row.getString("level_over_alloc_pct"));
      customProperties.put("LevelingPriorities", row.getString("levelprioritylist"));

      //
      // Schedule
      //
      customProperties.put("SetDataDateAndPlannedStartToProjectForecastStart", Boolean.valueOf(row.getBoolean("sched_setplantoforecast")));

      //
      // Schedule Options - General
      //
      customProperties.put("IgnoreRelationshipsToAndFromOtherProjects", row.getString("sched_outer_depend_type"));
      customProperties.put("MakeOpenEndedActivitiesCritical", Boolean.valueOf(row.getBoolean("sched_open_critical_flag")));
      customProperties.put("UseExpectedFinishDates", Boolean.valueOf(row.getBoolean("sched_use_expect_end_flag")));
      // Schedule automatically when a change affects dates
      // Level resources during scheduling
      customProperties.put("WhenSchedulingProgressedActivitiesUseRetainedLogic", Boolean.valueOf(row.getBoolean("sched_retained_logic")));
      customProperties.put("WhenSchedulingProgressedActivitiesUseProgressOverride", Boolean.valueOf(row.getBoolean("sched_progress_override")));
      customProperties.put("ComputeStartToStartLagFromEarlyStart", Boolean.valueOf(row.getBoolean("sched_lag_early_start_flag")));
      // Define critical activities as
      customProperties.put("CalculateFloatBasedOnFishDateOfEachProject", Boolean.valueOf(row.getBoolean("sched_use_project_end_date_for_float")));
      customProperties.put("ComputeTotalFloatAs", row.getString("sched_float_type"));
      customProperties.put("CalendarForSchedulingRelationshipLag", row.getString("sched_calendar_on_relationship_lag"));

      //
      // Schedule Options - Advanced
      //
      customProperties.put("CalculateMultipleFloatPaths", Boolean.valueOf(row.getBoolean("enable_multiple_longest_path_calc")));
      customProperties.put("CalculateMultiplePathsUsingTotalFloat", Boolean.valueOf(row.getBoolean("use_total_float_multiple_longest_paths")));
      customProperties.put("DisplayMultipleFloatPathsEndingWithActivity", row.getString("key_activity_for_multiple_longest_paths"));
      customProperties.put("LimitNumberOfPathsToCalculate", Boolean.valueOf(row.getBoolean("limit_multiple_longest_path_calc")));
      customProperties.put("NumberofPathsToCalculate", row.getString("max_multiple_longest_path"));

      m_project.getProjectProperties().setCustomProperties(customProperties);
   }

   /**
    * Generic method to extract Primavera fields and assign to MPXJ fields.
    *
    * @param map map of MPXJ field types and Primavera field names
    * @param row Primavera data container
    * @param container MPXJ data contain
    */
   private void processFields(Map<FieldType, String> map, Row row, FieldContainer container)
   {
      for (Map.Entry<FieldType, String> entry : map.entrySet())
      {
         FieldType field = entry.getKey();
         String name = entry.getValue();

         Object value;
         switch (field.getDataType())
         {
            case INTEGER:
            {
               value = row.getInteger(name);
               break;
            }

            case BOOLEAN:
            {
               value = Boolean.valueOf(row.getBoolean(name));
               break;
            }

            case DATE:
            {
               value = row.getDate(name);
               break;
            }

            case CURRENCY:
            case NUMERIC:
            case PERCENTAGE:
            {
               value = row.getDouble(name);
               break;
            }

            case DELAY:
            case WORK:
            case DURATION:
            {
               value = row.getDuration(name);
               break;
            }

            case RESOURCE_TYPE:
            {
               value = RESOURCE_TYPE_MAP.get(row.getString(name));
               break;
            }

            case TASK_TYPE:
            {
               value = TASK_TYPE_MAP.get(row.getString(name));
               break;
            }

            case CONSTRAINT:
            {
               value = CONSTRAINT_TYPE_MAP.get(row.getString(name));
               break;
            }

            case PRIORITY:
            {
               value = PRIORITY_MAP.get(row.getString(name));
               break;
            }

            case GUID:
            {
               value = row.getUUID(name);
               break;
            }

            default:
            {
               value = row.getString(name);
               break;
            }
         }

         container.set(field, value);
      }
   }

   /**
    * Calculate the physical percent complete.
    *
    * @param row task data
    * @return percent complete
    */
   private Number calculatePhysicalPercentComplete(Row row)
   {
      return row.getDouble("phys_complete_pct");
   }

   /**
    * Calculate the units percent complete.
    *
    * @param row task data
    * @return percent complete
    */
   private Number calculateUnitsPercentComplete(Row row)
   {
      double result = 0;

      double actualWorkQuantity = NumberHelper.getDouble(row.getDouble("act_work_qty"));
      double actualEquipmentQuantity = NumberHelper.getDouble(row.getDouble("act_equip_qty"));
      double numerator = actualWorkQuantity + actualEquipmentQuantity;

      if (numerator != 0)
      {
         double remainingWorkQuantity = NumberHelper.getDouble(row.getDouble("remain_work_qty"));
         double remainingEquipmentQuantity = NumberHelper.getDouble(row.getDouble("remain_equip_qty"));
         double denominator = remainingWorkQuantity + actualWorkQuantity + remainingEquipmentQuantity + actualEquipmentQuantity;
         result = denominator == 0 ? 0 : ((numerator * 100) / denominator);
      }

      return NumberHelper.getDouble(result);
   }

   /**
    * Calculate the duration percent complete.
    *
    * @param row task data
    * @return percent complete
    */
   private Number calculateDurationPercentComplete(Row row)
   {
      double result = 0;
      double targetDuration = row.getDuration("target_drtn_hr_cnt").getDuration();
      double remainingDuration = row.getDuration("remain_drtn_hr_cnt").getDuration();

      if (targetDuration == 0)
      {
         if (remainingDuration == 0)
         {
            if ("TK_Complete".equals(row.getString("status_code")))
            {
               result = 100;
            }
         }
      }
      else
      {
         if (remainingDuration < targetDuration)
         {
            result = ((targetDuration - remainingDuration) * 100) / targetDuration;
         }
      }

      return NumberHelper.getDouble(result);
   }

   /**
    * Retrieve the default mapping between MPXJ resource fields and Primavera resource field names.
    *
    * @return mapping
    */
   public static Map<FieldType, String> getDefaultResourceFieldMap()
   {
      Map<FieldType, String> map = new LinkedHashMap<>();

      map.put(ResourceField.UNIQUE_ID, "rsrc_id");
      map.put(ResourceField.GUID, "guid");
      map.put(ResourceField.NAME, "rsrc_name");
      map.put(ResourceField.CODE, "employee_code");
      map.put(ResourceField.EMAIL_ADDRESS, "email_addr");
      map.put(ResourceField.NOTES, "rsrc_notes");
      map.put(ResourceField.CREATED, "create_date");
      map.put(ResourceField.TYPE, "rsrc_type");
      map.put(ResourceField.PARENT_ID, "parent_rsrc_id");
      map.put(ResourceField.RESOURCE_ID, "rsrc_short_name");

      return map;
   }

   /**
    * Retrieve the default mapping between MPXJ resource fields and Primavera role field names.
    *
    * @return mapping
    */
   public static Map<FieldType, String> getDefaultRoleFieldMap()
   {
      Map<FieldType, String> map = new LinkedHashMap<>();

      map.put(ResourceField.UNIQUE_ID, "role_id");
      map.put(ResourceField.NAME, "role_name");
      map.put(ResourceField.RESOURCE_ID, "role_short_name");
      map.put(ResourceField.NOTES, "role_descr");
      map.put(ResourceField.PARENT_ID, "parent_role_id");

      return map;
   }

   /**
    * Retrieve the default mapping between MPXJ task fields and Primavera wbs field names.
    *
    * @return mapping
    */
   public static Map<FieldType, String> getDefaultWbsFieldMap()
   {
      Map<FieldType, String> map = new LinkedHashMap<>();

      map.put(TaskField.UNIQUE_ID, "wbs_id");
      map.put(TaskField.GUID, "guid");
      map.put(TaskField.NAME, "wbs_name");
      map.put(TaskField.REMAINING_COST, "indep_remain_total_cost");
      map.put(TaskField.REMAINING_WORK, "indep_remain_work_qty");
      map.put(TaskField.DEADLINE, "anticip_end_date");
      map.put(TaskField.WBS, "wbs_short_name");

      return map;
   }

   /**
    * Retrieve the default mapping between MPXJ task fields and Primavera task field names.
    *
    * @return mapping
    */
   public static Map<FieldType, String> getDefaultTaskFieldMap()
   {
      Map<FieldType, String> map = new LinkedHashMap<>();

      map.put(TaskField.UNIQUE_ID, "task_id");
      map.put(TaskField.GUID, "guid");
      map.put(TaskField.NAME, "task_name");
      map.put(TaskField.REMAINING_DURATION, "remain_drtn_hr_cnt");
      map.put(TaskField.ACTUAL_WORK, "act_work_qty");
      map.put(TaskField.REMAINING_WORK, "remain_work_qty");
      map.put(TaskField.PLANNED_WORK, "target_work_qty");
      map.put(TaskField.PLANNED_DURATION, "target_drtn_hr_cnt");
      map.put(TaskField.CONSTRAINT_DATE, "cstr_date");
      map.put(TaskField.ACTUAL_START, "act_start_date");
      map.put(TaskField.ACTUAL_FINISH, "act_end_date");
      map.put(TaskField.LATE_START, "late_start_date");
      map.put(TaskField.LATE_FINISH, "late_end_date");
      map.put(TaskField.EARLY_START, "early_start_date");
      map.put(TaskField.EARLY_FINISH, "early_end_date");
      map.put(TaskField.REMAINING_EARLY_START, "restart_date");
      map.put(TaskField.REMAINING_EARLY_FINISH, "reend_date");
      map.put(TaskField.REMAINING_LATE_START, "rem_late_start_date");
      map.put(TaskField.REMAINING_LATE_FINISH, "rem_late_end_date");
      map.put(TaskField.PLANNED_START, "target_start_date");
      map.put(TaskField.PLANNED_FINISH, "target_end_date");
      map.put(TaskField.CONSTRAINT_TYPE, "cstr_type");
      map.put(TaskField.SECONDARY_CONSTRAINT_DATE, "cstr_date2");
      map.put(TaskField.SECONDARY_CONSTRAINT_TYPE, "cstr_type2");
      map.put(TaskField.PRIORITY, "priority_type");
      map.put(TaskField.CREATED, "create_date");
      map.put(TaskField.TYPE, "duration_type");
      map.put(TaskField.FREE_SLACK, "free_float_hr_cnt");
      map.put(TaskField.TOTAL_SLACK, "total_float_hr_cnt");
      map.put(TaskField.ACTIVITY_ID, "task_code");
      map.put(TaskField.PRIMARY_RESOURCE_ID, "rsrc_id");
      map.put(TaskField.SUSPEND_DATE, "suspend_date");
      map.put(TaskField.RESUME, "resume_date");
      map.put(TaskField.EXTERNAL_EARLY_START, "external_early_start_date");
      map.put(TaskField.EXTERNAL_LATE_FINISH, "external_late_end_date");
      map.put(TaskField.LONGEST_PATH, "driving_path_flag");

      return map;
   }

   /**
    * Retrieve the default mapping between MPXJ assignment fields and Primavera assignment field names.
    *
    * @return mapping
    */
   public static Map<FieldType, String> getDefaultAssignmentFieldMap()
   {
      Map<FieldType, String> map = new LinkedHashMap<>();

      map.put(AssignmentField.UNIQUE_ID, "taskrsrc_id");
      map.put(AssignmentField.GUID, "guid");
      map.put(AssignmentField.REMAINING_WORK, "remain_qty");
      map.put(AssignmentField.PLANNED_WORK, "target_qty");
      map.put(AssignmentField.ACTUAL_OVERTIME_WORK, "act_ot_qty");
      map.put(AssignmentField.PLANNED_COST, "target_cost");
      map.put(AssignmentField.ACTUAL_OVERTIME_COST, "act_ot_cost");
      map.put(AssignmentField.REMAINING_COST, "remain_cost");
      map.put(AssignmentField.ACTUAL_START, "act_start_date");
      map.put(AssignmentField.ACTUAL_FINISH, "act_end_date");
      map.put(AssignmentField.PLANNED_START, "target_start_date");
      map.put(AssignmentField.PLANNED_FINISH, "target_end_date");
      map.put(AssignmentField.ASSIGNMENT_DELAY, "target_lag_drtn_hr_cnt");

      return map;
   }

   private final ProjectFile m_project;
   private final EventManager m_eventManager;
   private final ClashMap m_activityClashMap = new ClashMap();
   private final ClashMap m_roleClashMap = new ClashMap();
   private final DateFormat m_calendarTimeFormat = new SimpleDateFormat("HH:mm");
   private Integer m_defaultCalendarID;

   private final UserFieldCounters m_taskUdfCounters;
   private final UserFieldCounters m_resourceUdfCounters;
   private final UserFieldCounters m_assignmentUdfCounters;
   private final Map<FieldType, String> m_resourceFields;
   private final Map<FieldType, String> m_roleFields;
   private final Map<FieldType, String> m_wbsFields;
   private final Map<FieldType, String> m_taskFields;
   private final Map<FieldType, String> m_assignmentFields;
   private final List<ExternalRelation> m_externalRelations = new ArrayList<>();
   private final boolean m_matchPrimaveraWBS;
   private final boolean m_wbsIsFullPath;

   private final Map<Integer, FieldType> m_udfFields = new HashMap<>();
   private final Map<String, Map<Integer, List<Row>>> m_udfValues = new HashMap<>();

   private final Map<Integer, ActivityCodeValue> m_activityCodeMap = new HashMap<>();
   private final Map<Integer, List<Integer>> m_activityCodeAssignments = new HashMap<>();

   private static final Map<String, ResourceType> RESOURCE_TYPE_MAP = new HashMap<>();
   static
   {
      RESOURCE_TYPE_MAP.put(null, ResourceType.WORK);
      RESOURCE_TYPE_MAP.put("RT_Labor", ResourceType.WORK);
      RESOURCE_TYPE_MAP.put("RT_Mat", ResourceType.MATERIAL);
      RESOURCE_TYPE_MAP.put("RT_Equip", ResourceType.COST);
   }

   private static final Map<String, ConstraintType> CONSTRAINT_TYPE_MAP = new HashMap<>();
   static
   {
      CONSTRAINT_TYPE_MAP.put("CS_MSO", ConstraintType.START_ON);
      CONSTRAINT_TYPE_MAP.put("CS_MSOB", ConstraintType.START_NO_LATER_THAN);
      CONSTRAINT_TYPE_MAP.put("CS_MSOA", ConstraintType.START_NO_EARLIER_THAN);
      CONSTRAINT_TYPE_MAP.put("CS_MEO", ConstraintType.FINISH_ON);
      CONSTRAINT_TYPE_MAP.put("CS_MEOB", ConstraintType.FINISH_NO_LATER_THAN);
      CONSTRAINT_TYPE_MAP.put("CS_MEOA", ConstraintType.FINISH_NO_EARLIER_THAN);
      CONSTRAINT_TYPE_MAP.put("CS_ALAP", ConstraintType.AS_LATE_AS_POSSIBLE);
      CONSTRAINT_TYPE_MAP.put("CS_MANDSTART", ConstraintType.MUST_START_ON);
      CONSTRAINT_TYPE_MAP.put("CS_MANDFIN", ConstraintType.MUST_FINISH_ON);
   }

   private static final Map<String, Priority> PRIORITY_MAP = new HashMap<>();
   static
   {
      PRIORITY_MAP.put("PT_Top", Priority.getInstance(Priority.HIGHEST));
      PRIORITY_MAP.put("PT_High", Priority.getInstance(Priority.HIGH));
      PRIORITY_MAP.put("PT_Normal", Priority.getInstance(Priority.MEDIUM));
      PRIORITY_MAP.put("PT_Low", Priority.getInstance(Priority.LOW));
      PRIORITY_MAP.put("PT_Lowest", Priority.getInstance(Priority.LOWEST));
   }

   private static final Map<String, RelationType> RELATION_TYPE_MAP = new HashMap<>();
   static
   {
      RELATION_TYPE_MAP.put("PR_FS", RelationType.FINISH_START);
      RELATION_TYPE_MAP.put("PR_FF", RelationType.FINISH_FINISH);
      RELATION_TYPE_MAP.put("PR_SS", RelationType.START_START);
      RELATION_TYPE_MAP.put("PR_SF", RelationType.START_FINISH);
   }

   private static final Map<String, TaskType> TASK_TYPE_MAP = new HashMap<>();
   static
   {
      TASK_TYPE_MAP.put("DT_FixedDrtn", TaskType.FIXED_DURATION);
      TASK_TYPE_MAP.put("DT_FixedQty", TaskType.FIXED_UNITS);
      TASK_TYPE_MAP.put("DT_FixedDUR2", TaskType.FIXED_WORK);
      TASK_TYPE_MAP.put("DT_FixedRate", TaskType.FIXED_WORK);
   }

   private static final Map<String, Boolean> MILESTONE_MAP = new HashMap<>();
   static
   {
      MILESTONE_MAP.put("TT_Task", Boolean.FALSE);
      MILESTONE_MAP.put("TT_Rsrc", Boolean.FALSE);
      MILESTONE_MAP.put("TT_LOE", Boolean.FALSE);
      MILESTONE_MAP.put("TT_Mile", Boolean.TRUE);
      MILESTONE_MAP.put("TT_FinMile", Boolean.TRUE);
      MILESTONE_MAP.put("TT_WBS", Boolean.FALSE);
   }

   private static final Map<String, ActivityType> ACTIVITY_TYPE_MAP = new HashMap<>();
   static
   {
      ACTIVITY_TYPE_MAP.put("TT_Task", ActivityType.TASK_DEPENDENT);
      ACTIVITY_TYPE_MAP.put("TT_Rsrc", ActivityType.RESOURCE_DEPENDENT);
      ACTIVITY_TYPE_MAP.put("TT_LOE", ActivityType.LEVEL_OF_EFFORT);
      ACTIVITY_TYPE_MAP.put("TT_Mile", ActivityType.START_MILESTONE);
      ACTIVITY_TYPE_MAP.put("TT_FinMile", ActivityType.FINISH_MILESTONE);
      ACTIVITY_TYPE_MAP.put("TT_WBS", ActivityType.WBS_SUMMARY);
   }

   private static final Map<String, TimeUnit> TIME_UNIT_MAP = new HashMap<>();
   static
   {
      TIME_UNIT_MAP.put("QT_Minute", TimeUnit.MINUTES);
      TIME_UNIT_MAP.put("QT_Hour", TimeUnit.HOURS);
      TIME_UNIT_MAP.put("QT_Day", TimeUnit.DAYS);
      TIME_UNIT_MAP.put("QT_Week", TimeUnit.WEEKS);
      TIME_UNIT_MAP.put("QT_Month", TimeUnit.MONTHS);
      TIME_UNIT_MAP.put("QT_Year", TimeUnit.YEARS);
   }

   private static final Map<String, CurrencySymbolPosition> CURRENCY_SYMBOL_POSITION_MAP = new HashMap<>();
   static
   {
      CURRENCY_SYMBOL_POSITION_MAP.put("#1.1", CurrencySymbolPosition.BEFORE);
      CURRENCY_SYMBOL_POSITION_MAP.put("1.1#", CurrencySymbolPosition.AFTER);
      CURRENCY_SYMBOL_POSITION_MAP.put("# 1.1", CurrencySymbolPosition.BEFORE_WITH_SPACE);
      CURRENCY_SYMBOL_POSITION_MAP.put("1.1 #", CurrencySymbolPosition.AFTER_WITH_SPACE);
   }

   private static final Map<String, Boolean> STATICTYPE_UDF_MAP = new HashMap<>();
   static
   {
      // this is a judgement call on how the static type indicator values would be best translated to a flag
      STATICTYPE_UDF_MAP.put("UDF_G0", Boolean.FALSE); // no indicator
      STATICTYPE_UDF_MAP.put("UDF_G1", Boolean.FALSE); // red x
      STATICTYPE_UDF_MAP.put("UDF_G2", Boolean.FALSE); // yellow !
      STATICTYPE_UDF_MAP.put("UDF_G3", Boolean.TRUE); // green check
      STATICTYPE_UDF_MAP.put("UDF_G4", Boolean.TRUE); // blue star
   }

   private static final Map<String, FieldTypeClass> FIELD_TYPE_MAP = new HashMap<>();
   static
   {
      FIELD_TYPE_MAP.put("PROJWBS", FieldTypeClass.TASK);
      FIELD_TYPE_MAP.put("TASK", FieldTypeClass.TASK);
      FIELD_TYPE_MAP.put("RSRC", FieldTypeClass.RESOURCE);
      FIELD_TYPE_MAP.put("TASKRSRC", FieldTypeClass.ASSIGNMENT);
   }

   private static final Map<String, AccrueType> ACCRUE_TYPE_MAP = new HashMap<>();
   static
   {
      ACCRUE_TYPE_MAP.put("CL_Uniform", AccrueType.PRORATED);
      ACCRUE_TYPE_MAP.put("CL_End", AccrueType.END);
      ACCRUE_TYPE_MAP.put("CL_Start", AccrueType.START);
   }

   private static final Map<String, PercentCompleteType> PERCENT_COMPLETE_TYPE = new HashMap<>();
   static
   {
      PERCENT_COMPLETE_TYPE.put("CP_Phys", PercentCompleteType.PHYSICAL);
      PERCENT_COMPLETE_TYPE.put("CP_Drtn", PercentCompleteType.DURATION);
      PERCENT_COMPLETE_TYPE.put("CP_Units", PercentCompleteType.UNITS);
   }

   private static final Map<String, ActivityStatus> STATUS_MAP = new HashMap<>();
   static
   {
      STATUS_MAP.put("TK_NotStart", ActivityStatus.NOT_STARTED);
      STATUS_MAP.put("TK_Active", ActivityStatus.IN_PROGRESS);
      STATUS_MAP.put("TK_Complete", ActivityStatus.COMPLETED);
   }

   private static final Map<String, CriticalActivityType> CRITICAL_ACTIVITY_MAP = new HashMap<>();
   static
   {
      CRITICAL_ACTIVITY_MAP.put("CT_TotFloat", CriticalActivityType.TOTAL_FLOAT);
      CRITICAL_ACTIVITY_MAP.put("CT_DrivPath", CriticalActivityType.LONGEST_PATH);
   }

   private static final Map<String, net.sf.mpxj.CalendarType> CALENDAR_TYPE_MAP = new HashMap<>();
   static
   {
      CALENDAR_TYPE_MAP.put("CA_Base", net.sf.mpxj.CalendarType.GLOBAL);
      CALENDAR_TYPE_MAP.put("CA_Project", net.sf.mpxj.CalendarType.PROJECT);
      CALENDAR_TYPE_MAP.put("CA_Rsrc", net.sf.mpxj.CalendarType.RESOURCE);
   }

   private static final Map<String, ActivityCodeScope> ACTIVITY_CODE_SCOPE_MAP = new HashMap<>();
   static
   {
      ACTIVITY_CODE_SCOPE_MAP.put("AS_Global", ActivityCodeScope.GLOBAL);
      ACTIVITY_CODE_SCOPE_MAP.put("AS_EPS", ActivityCodeScope.EPS);
      ACTIVITY_CODE_SCOPE_MAP.put("AS_Project", ActivityCodeScope.PROJECT);
   }

   private static final long EXCEPTION_EPOCH = -2209161599935L;

   static final String DEFAULT_WBS_SEPARATOR = ".";
}