/*
 * file:       GanttProjectReader.java
 * author:     Jon Iles
 * copyright:  (c) Packwood Software 2017
 * date:       22 March 2017
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

package net.sf.mpxj.ganttproject;

import java.io.InputStream;
import java.text.DateFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.xml.bind.JAXBContext;
import javax.xml.bind.JAXBException;
import javax.xml.parsers.ParserConfigurationException;

import org.xml.sax.SAXException;

import net.sf.mpxj.ChildTaskContainer;
import net.sf.mpxj.ConstraintType;
import net.sf.mpxj.CustomField;
import net.sf.mpxj.DataType;
import net.sf.mpxj.Day;
import net.sf.mpxj.Duration;
import net.sf.mpxj.EventManager;
import net.sf.mpxj.FieldType;
import net.sf.mpxj.MPXJException;
import net.sf.mpxj.Priority;
import net.sf.mpxj.ProjectCalendar;
import net.sf.mpxj.ProjectCalendarDays;
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
import net.sf.mpxj.Task;
import net.sf.mpxj.TimeUnit;
import net.sf.mpxj.common.DateHelper;
import net.sf.mpxj.common.NumberHelper;
import net.sf.mpxj.common.Pair;
import net.sf.mpxj.common.ResourceFieldLists;
import net.sf.mpxj.common.TaskFieldLists;
import net.sf.mpxj.common.UnmarshalHelper;
import net.sf.mpxj.ganttproject.schema.Allocation;
import net.sf.mpxj.ganttproject.schema.Allocations;
import net.sf.mpxj.ganttproject.schema.Calendars;
import net.sf.mpxj.ganttproject.schema.CustomPropertyDefinition;
import net.sf.mpxj.ganttproject.schema.CustomResourceProperty;
import net.sf.mpxj.ganttproject.schema.CustomTaskProperty;
import net.sf.mpxj.ganttproject.schema.DayTypes;
import net.sf.mpxj.ganttproject.schema.DefaultWeek;
import net.sf.mpxj.ganttproject.schema.Depend;
import net.sf.mpxj.ganttproject.schema.Project;
import net.sf.mpxj.ganttproject.schema.Resources;
import net.sf.mpxj.ganttproject.schema.Role;
import net.sf.mpxj.ganttproject.schema.Roles;
import net.sf.mpxj.ganttproject.schema.Taskproperty;
import net.sf.mpxj.ganttproject.schema.Tasks;
import net.sf.mpxj.reader.AbstractProjectStreamReader;

/**
 * This class creates a new ProjectFile instance by reading a GanttProject file.
 */
public final class GanttProjectReader extends AbstractProjectStreamReader
{
   @Override public ProjectFile read(InputStream stream) throws MPXJException
   {
      try
      {
         if (CONTEXT == null)
         {
            throw CONTEXT_EXCEPTION;
         }

         m_projectFile = new ProjectFile();
         m_eventManager = m_projectFile.getEventManager();
         m_resourcePropertyDefinitions = new HashMap<>();
         m_taskPropertyDefinitions = new HashMap<>();
         m_roleDefinitions = new HashMap<>();
         m_dateFormat = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SS'Z'");
         m_resourcePropertyTypes = getResourcePropertyTypes();
         m_taskPropertyTypes = getTaskPropertyTypes();

         ProjectConfig config = m_projectFile.getProjectConfig();
         config.setAutoResourceUniqueID(false);
         config.setAutoTaskUniqueID(false);
         config.setAutoOutlineLevel(true);
         config.setAutoOutlineNumber(true);
         config.setAutoWBS(true);

         m_projectFile.getProjectProperties().setFileApplication("GanttProject");
         m_projectFile.getProjectProperties().setFileType("GAN");

         addListenersToProject(m_projectFile);

         Project ganttProject = (Project) UnmarshalHelper.unmarshal(CONTEXT, stream);

         readProjectProperties(ganttProject);
         readCalendars(ganttProject);
         readResources(ganttProject);
         readTasks(ganttProject);
         readRelationships(ganttProject);
         readResourceAssignments(ganttProject);

         //
         // Ensure that the unique ID counters are correct
         //
         config.updateUniqueCounters();

         return m_projectFile;
      }

      catch (ParserConfigurationException | SAXException | JAXBException ex)
      {
         throw new MPXJException("Failed to parse file", ex);
      }

      finally
      {
         m_projectFile = null;
         m_mpxjCalendar = null;
         m_eventManager = null;
         m_localeDateFormat = null;
         m_resourcePropertyDefinitions = null;
         m_taskPropertyDefinitions = null;
         m_roleDefinitions = null;
         m_resourcePropertyTypes = null;
         m_taskPropertyTypes = null;
      }
   }

   @Override public List<ProjectFile> readAll(InputStream inputStream) throws MPXJException
   {
      return Collections.singletonList(read(inputStream));
   }

   /**
    * This method extracts project properties from a GanttProject file.
    *
    * @param ganttProject GanttProject file
    */
   private void readProjectProperties(Project ganttProject)
   {
      ProjectProperties mpxjProperties = m_projectFile.getProjectProperties();
      mpxjProperties.setName(ganttProject.getName());
      mpxjProperties.setCompany(ganttProject.getCompany());
      mpxjProperties.setDefaultDurationUnits(TimeUnit.DAYS);

      String locale = ganttProject.getLocale();
      if (locale == null)
      {
         locale = "en-US";
      }
      else
      {
         locale = locale.replace('_', '-');
      }

      m_localeDateFormat = DateFormat.getDateInstance(DateFormat.SHORT, Locale.forLanguageTag(locale));
   }

   /**
    * This method extracts calendar data from a GanttProject file.
    *
    * @param ganttProject Root node of the GanttProject file
    */
   private void readCalendars(Project ganttProject)
   {
      m_mpxjCalendar = m_projectFile.addCalendar();
      m_mpxjCalendar.setName(ProjectCalendar.DEFAULT_BASE_CALENDAR_NAME);
      m_projectFile.getProjectProperties().setDefaultCalendar(m_mpxjCalendar);

      Calendars gpCalendar = ganttProject.getCalendars();
      setWorkingDays(m_mpxjCalendar, gpCalendar);
      setExceptions(m_mpxjCalendar, gpCalendar);
      m_eventManager.fireCalendarReadEvent(m_mpxjCalendar);
   }

   /**
    * Add working days and working time to a calendar.
    *
    * @param mpxjCalendar MPXJ calendar
    * @param gpCalendar GanttProject calendar
    */
   private void setWorkingDays(ProjectCalendar mpxjCalendar, Calendars gpCalendar)
   {
      DayTypes dayTypes = gpCalendar.getDayTypes();
      DefaultWeek defaultWeek = dayTypes.getDefaultWeek();
      if (defaultWeek == null)
      {
         mpxjCalendar.setWorkingDay(Day.SUNDAY, false);
         mpxjCalendar.setWorkingDay(Day.MONDAY, true);
         mpxjCalendar.setWorkingDay(Day.TUESDAY, true);
         mpxjCalendar.setWorkingDay(Day.WEDNESDAY, true);
         mpxjCalendar.setWorkingDay(Day.THURSDAY, true);
         mpxjCalendar.setWorkingDay(Day.FRIDAY, true);
         mpxjCalendar.setWorkingDay(Day.SATURDAY, false);
      }
      else
      {
         mpxjCalendar.setWorkingDay(Day.MONDAY, isWorkingDay(defaultWeek.getMon()));
         mpxjCalendar.setWorkingDay(Day.TUESDAY, isWorkingDay(defaultWeek.getTue()));
         mpxjCalendar.setWorkingDay(Day.WEDNESDAY, isWorkingDay(defaultWeek.getWed()));
         mpxjCalendar.setWorkingDay(Day.THURSDAY, isWorkingDay(defaultWeek.getThu()));
         mpxjCalendar.setWorkingDay(Day.FRIDAY, isWorkingDay(defaultWeek.getFri()));
         mpxjCalendar.setWorkingDay(Day.SATURDAY, isWorkingDay(defaultWeek.getSat()));
         mpxjCalendar.setWorkingDay(Day.SUNDAY, isWorkingDay(defaultWeek.getSun()));
      }

      for (Day day : Day.values())
      {
         ProjectCalendarHours hours = mpxjCalendar.addCalendarHours(day);
         if (mpxjCalendar.isWorkingDay(day))
         {
            hours.add(ProjectCalendarDays.DEFAULT_WORKING_MORNING);
            hours.add(ProjectCalendarDays.DEFAULT_WORKING_AFTERNOON);
         }
      }
   }

   /**
    * Returns true if the flag indicates a working day.
    *
    * @param value flag value
    * @return true if this is a working day
    */
   private boolean isWorkingDay(Integer value)
   {
      return NumberHelper.getInt(value) == 0;
   }

   /**
    * Add exceptions to the calendar.
    *
    * @param mpxjCalendar MPXJ calendar
    * @param gpCalendar GanttProject calendar
    */
   private void setExceptions(ProjectCalendar mpxjCalendar, Calendars gpCalendar)
   {
      List<net.sf.mpxj.ganttproject.schema.Date> dates = gpCalendar.getDate();
      for (net.sf.mpxj.ganttproject.schema.Date date : dates)
      {
         addException(mpxjCalendar, date);
      }
   }

   /**
    * Add a single exception to a calendar.
    *
    * @param mpxjCalendar MPXJ calendar
    * @param date calendar exception
    */
   private void addException(ProjectCalendar mpxjCalendar, net.sf.mpxj.ganttproject.schema.Date date)
   {
      String year = date.getYear();
      if (year == null || year.isEmpty())
      {
         // In order to process recurring exceptions using MPXJ, we need a start and end date
         // to constrain the number of dates we generate.
         // May need to pre-process the tasks in order to calculate a start and finish date.
         // TODO: handle recurring exceptions
      }
      else
      {
         Calendar calendar = DateHelper.popCalendar();
         calendar.set(Calendar.YEAR, Integer.parseInt(year));
         calendar.set(Calendar.MONTH, NumberHelper.getInt(date.getMonth()));
         calendar.set(Calendar.DAY_OF_MONTH, NumberHelper.getInt(date.getDate()));
         Date exceptionDate = calendar.getTime();
         DateHelper.pushCalendar(calendar);
         ProjectCalendarException exception = mpxjCalendar.addCalendarException(exceptionDate);

         // TODO: not sure how NEUTRAL should be handled
         if ("WORKING_DAY".equals(date.getType()))
         {
            exception.add(ProjectCalendarDays.DEFAULT_WORKING_MORNING);
            exception.add(ProjectCalendarDays.DEFAULT_WORKING_AFTERNOON);
         }
      }
   }

   /**
    * This method extracts resource data from a GanttProject file.
    *
    * @param ganttProject parent node for resources
    */
   private void readResources(Project ganttProject)
   {
      Resources resources = ganttProject.getResources();
      readResourceCustomPropertyDefinitions(resources);
      readRoleDefinitions(ganttProject);

      for (net.sf.mpxj.ganttproject.schema.Resource gpResource : resources.getResource())
      {
         readResource(gpResource);
      }
   }

   /**
    * Read custom property definitions for resources.
    *
    * @param gpResources GanttProject resources
    */
   private void readResourceCustomPropertyDefinitions(Resources gpResources)
   {
      for (CustomPropertyDefinition definition : gpResources.getCustomPropertyDefinition())
      {
         //
         // Find the next available field of the correct type.
         //
         String type = definition.getType();
         FieldType fieldType = m_resourcePropertyTypes.get(type).getField();

         //
         // If we have run out of fields of the right type, try using a text field.
         //
         if (fieldType == null)
         {
            fieldType = m_resourcePropertyTypes.get("text").getField();
         }

         //
         // If we actually have a field available, set the alias to match
         // the name used in GanttProject.
         //
         if (fieldType != null)
         {
            CustomField field = m_projectFile.getCustomFields().getCustomField(fieldType);
            field.setAlias(definition.getName());
            String defaultValue = definition.getDefaultValue();
            if (defaultValue != null && defaultValue.isEmpty())
            {
               defaultValue = null;
            }
            m_resourcePropertyDefinitions.put(definition.getId(), new Pair<>(fieldType, parseValue(fieldType.getDataType(), m_localeDateFormat, defaultValue)));
         }
      }
   }

   /**
    * Read custom property definitions for tasks.
    *
    * @param gpTasks GanttProject tasks
    */
   private void readTaskCustomPropertyDefinitions(Tasks gpTasks)
   {
      for (Taskproperty definition : gpTasks.getTaskproperties().getTaskproperty())
      {
         //
         // Ignore everything but custom values
         //
         if (!"custom".equals(definition.getType()))
         {
            continue;
         }

         //
         // Find the next available field of the correct type.
         //
         String type = definition.getValuetype();
         FieldType fieldType = m_taskPropertyTypes.get(type).getField();

         //
         // If we have run out of fields of the right type, try using a text field.
         //
         if (fieldType == null)
         {
            fieldType = m_taskPropertyTypes.get("text").getField();
         }

         //
         // If we actually have a field available, set the alias to match
         // the name used in GanttProject.
         //
         if (fieldType != null)
         {
            CustomField field = m_projectFile.getCustomFields().getCustomField(fieldType);
            field.setAlias(definition.getName());
            String defaultValue = definition.getDefaultvalue();
            if (defaultValue != null && defaultValue.isEmpty())
            {
               defaultValue = null;
            }
            m_taskPropertyDefinitions.put(definition.getId(), new Pair<>(fieldType, parseValue(fieldType.getDataType(), m_dateFormat, defaultValue)));
         }
      }
   }

   /**
    * Read the role definitions from a GanttProject project.
    *
    * @param gpProject GanttProject project
    */
   private void readRoleDefinitions(Project gpProject)
   {
      m_roleDefinitions.put("Default:1", "project manager");

      for (Roles roles : gpProject.getRoles())
      {
         if ("Default".equals(roles.getRolesetName()))
         {
            continue;
         }

         for (Role role : roles.getRole())
         {
            m_roleDefinitions.put(role.getId(), role.getName());
         }
      }
   }

   /**
    * This method extracts data for a single resource from a GanttProject file.
    *
    * @param gpResource resource data
    */
   private void readResource(net.sf.mpxj.ganttproject.schema.Resource gpResource)
   {
      Resource mpxjResource = m_projectFile.addResource();
      mpxjResource.setUniqueID(Integer.valueOf(NumberHelper.getInt(gpResource.getId()) + 1));
      mpxjResource.setName(gpResource.getName());
      mpxjResource.setEmailAddress(gpResource.getContacts());
      mpxjResource.setPhone(gpResource.getPhone());
      mpxjResource.setGroup(m_roleDefinitions.get(gpResource.getFunction()));

      net.sf.mpxj.ganttproject.schema.Rate gpRate = gpResource.getRate();
      if (gpRate != null)
      {
         mpxjResource.setStandardRate(new Rate(gpRate.getValueAttribute(), TimeUnit.DAYS));
      }
      readResourceCustomFields(gpResource, mpxjResource);
      m_eventManager.fireResourceReadEvent(mpxjResource);
   }

   /**
    * Read custom fields for a GanttProject resource.
    *
    * @param gpResource GanttProject resource
    * @param mpxjResource MPXJ Resource instance
    */
   private void readResourceCustomFields(net.sf.mpxj.ganttproject.schema.Resource gpResource, Resource mpxjResource)
   {
      //
      // Populate custom field default values
      //
      Map<FieldType, Object> customFields = new HashMap<>();
      for (Pair<FieldType, Object> definition : m_resourcePropertyDefinitions.values())
      {
         customFields.put(definition.getFirst(), definition.getSecond());
      }

      //
      // Update with custom field actual values
      //
      for (CustomResourceProperty property : gpResource.getCustomProperty())
      {
         Pair<FieldType, Object> definition = m_resourcePropertyDefinitions.get(property.getDefinitionId());
         if (definition != null)
         {
            //
            // Retrieve the value. If it is empty, use the default.
            //
            String value = property.getValueAttribute();
            if (value.isEmpty())
            {
               value = null;
            }

            //
            // If we have a value,convert it to the correct type
            //
            Object result = parseValue(definition.getFirst().getDataType(), m_localeDateFormat, value);
            if (result != null)
            {
               customFields.put(definition.getFirst(), result);
            }
         }
      }

      for (Map.Entry<FieldType, Object> item : customFields.entrySet())
      {
         if (item.getValue() != null)
         {
            mpxjResource.set(item.getKey(), item.getValue());
         }
      }
   }

   /**
    * Read custom fields for a GanttProject task.
    *
    * @param gpTask GanttProject task
    * @param mpxjTask MPXJ Task instance
    */
   private void readTaskCustomFields(net.sf.mpxj.ganttproject.schema.Task gpTask, Task mpxjTask)
   {
      //
      // Populate custom field default values
      //
      Map<FieldType, Object> customFields = new HashMap<>();
      for (Pair<FieldType, Object> definition : m_taskPropertyDefinitions.values())
      {
         customFields.put(definition.getFirst(), definition.getSecond());
      }

      //
      // Update with custom field actual values
      //
      for (CustomTaskProperty property : gpTask.getCustomproperty())
      {
         Pair<FieldType, Object> definition = m_taskPropertyDefinitions.get(property.getTaskpropertyId());
         if (definition != null)
         {
            //
            // Retrieve the value. If it is empty, use the default.
            //
            String value = property.getValueAttribute();
            if (value.isEmpty())
            {
               value = null;
            }

            //
            // If we have a value,convert it to the correct type
            //
            Object result = parseValue(definition.getFirst().getDataType(), m_dateFormat, value);
            if (result != null)
            {
               customFields.put(definition.getFirst(), result);
            }
         }
      }

      for (Map.Entry<FieldType, Object> item : customFields.entrySet())
      {
         if (item.getValue() != null)
         {
            mpxjTask.set(item.getKey(), item.getValue());
         }
      }
   }

   private Object parseValue(DataType type, DateFormat dateFormat, String value)
   {
      Object result = null;

      if (value != null)
      {
         switch (type)
         {
            case NUMERIC:
            {
               if (value.indexOf('.') == -1)
               {
                  result = Integer.valueOf(value);
               }
               else
               {
                  result = Double.valueOf(value);
               }
               break;
            }

            case DATE:
            {
               try
               {
                  result = dateFormat.parse(value);
               }
               catch (ParseException ex)
               {
                  // Ignore the error and return null
               }
               break;
            }

            case BOOLEAN:
            {
               result = Boolean.valueOf(value.equals("true"));
               break;
            }

            default:
            {
               result = value;
               break;
            }
         }
      }

      return result;
   }

   /**
    * Read the top level tasks from GanttProject.
    *
    * @param gpProject GanttProject project
    */
   private void readTasks(Project gpProject)
   {
      Tasks tasks = gpProject.getTasks();
      readTaskCustomPropertyDefinitions(tasks);
      for (net.sf.mpxj.ganttproject.schema.Task task : tasks.getTask())
      {
         readTask(m_projectFile, task);
      }
   }

   /**
    * Recursively read a task, and any sub-tasks.
    *
    * @param mpxjParent Parent for the MPXJ tasks
    * @param gpTask GanttProject task
    */
   private void readTask(ChildTaskContainer mpxjParent, net.sf.mpxj.ganttproject.schema.Task gpTask)
   {
      Task mpxjTask = mpxjParent.addTask();
      mpxjTask.setUniqueID(Integer.valueOf(NumberHelper.getInt(gpTask.getId()) + 1));
      mpxjTask.setName(gpTask.getName());
      mpxjTask.setPercentageComplete(gpTask.getComplete());
      mpxjTask.setPriority(getPriority(gpTask.getPriority()));
      mpxjTask.setHyperlink(gpTask.getWebLink());

      Duration duration = Duration.getInstance(NumberHelper.getDouble(gpTask.getDuration()), TimeUnit.DAYS);
      mpxjTask.setDuration(duration);

      if (duration.getDuration() == 0)
      {
         mpxjTask.setMilestone(true);
         mpxjTask.setStart(gpTask.getStart());
         mpxjTask.setFinish(gpTask.getStart());
      }
      else
      {
         mpxjTask.setStart(gpTask.getStart());
         mpxjTask.setFinish(m_mpxjCalendar.getDate(gpTask.getStart(), mpxjTask.getDuration(), false));
      }

      mpxjTask.setConstraintDate(gpTask.getThirdDate());
      if (mpxjTask.getConstraintDate() != null)
      {
         // TODO: you don't appear to be able to change this setting in GanttProject
         // task.getThirdDateConstraint()
         mpxjTask.setConstraintType(ConstraintType.START_NO_EARLIER_THAN);
      }

      readTaskCustomFields(gpTask, mpxjTask);

      // We don't have early/late start/finish.
      // Set attributes here to avoid trying to calculate them.
      mpxjTask.setStartSlack(Duration.getInstance(0, TimeUnit.DAYS));
      mpxjTask.setFinishSlack(Duration.getInstance(0, TimeUnit.DAYS));
      mpxjTask.setCritical(false);

      m_eventManager.fireTaskReadEvent(mpxjTask);

      // TODO: read custom values

      //
      // Process child tasks
      //
      for (net.sf.mpxj.ganttproject.schema.Task childTask : gpTask.getTask())
      {
         readTask(mpxjTask, childTask);
      }
   }

   /**
    * Given a GanttProject priority value, turn this into an MPXJ Priority instance.
    *
    * @param gpPriority GanttProject priority
    * @return Priority instance
    */
   private Priority getPriority(Integer gpPriority)
   {
      int result;
      if (gpPriority == null)
      {
         result = Priority.MEDIUM;
      }
      else
      {
         int index = gpPriority.intValue();
         if (index < 0 || index >= PRIORITY.length)
         {
            result = Priority.MEDIUM;
         }
         else
         {
            result = PRIORITY[index];
         }
      }
      return Priority.getInstance(result);
   }

   /**
    * Read all task relationships from a GanttProject.
    *
    * @param gpProject GanttProject project
    */
   private void readRelationships(Project gpProject)
   {
      for (net.sf.mpxj.ganttproject.schema.Task gpTask : gpProject.getTasks().getTask())
      {
         readRelationships(gpTask);
      }
   }

   /**
    * Read the relationships for an individual GanttProject task.
    *
    * @param gpTask GanttProject task
    */
   private void readRelationships(net.sf.mpxj.ganttproject.schema.Task gpTask)
   {
      for (Depend depend : gpTask.getDepend())
      {
         Task task1 = m_projectFile.getTaskByUniqueID(Integer.valueOf(NumberHelper.getInt(gpTask.getId()) + 1));
         Task task2 = m_projectFile.getTaskByUniqueID(Integer.valueOf(NumberHelper.getInt(depend.getId()) + 1));
         if (task1 != null && task2 != null)
         {
            Duration lag = Duration.getInstance(NumberHelper.getInt(depend.getDifference()), TimeUnit.DAYS);
            Relation relation = task2.addPredecessor(task1, getRelationType(depend.getType()), lag);
            m_eventManager.fireRelationReadEvent(relation);
         }
      }
   }

   /**
    * Convert a GanttProject task relationship type into an MPXJ RelationType instance.
    *
    * @param gpType GanttProject task relation type
    * @return RelationType instance
    */
   private RelationType getRelationType(Integer gpType)
   {
      RelationType result = null;
      if (gpType != null)
      {
         int index = NumberHelper.getInt(gpType);
         if (index > 0 && index < RELATION.length)
         {
            result = RELATION[index];
         }
      }

      if (result == null)
      {
         result = RelationType.FINISH_START;
      }

      return result;
   }

   /**
    * Read all resource assignments from a GanttProject project.
    *
    * @param gpProject GanttProject project
    */
   private void readResourceAssignments(Project gpProject)
   {
      Allocations allocations = gpProject.getAllocations();
      if (allocations != null)
      {
         for (Allocation allocation : allocations.getAllocation())
         {
            readResourceAssignment(allocation);
         }
      }
   }

   /**
    * Read an individual GanttProject resource assignment.
    *
    * @param gpAllocation GanttProject resource assignment.
    */
   private void readResourceAssignment(Allocation gpAllocation)
   {
      Integer taskID = Integer.valueOf(NumberHelper.getInt(gpAllocation.getTaskId()) + 1);
      Integer resourceID = Integer.valueOf(NumberHelper.getInt(gpAllocation.getResourceId()) + 1);
      Task task = m_projectFile.getTaskByUniqueID(taskID);
      Resource resource = m_projectFile.getResourceByUniqueID(resourceID);
      if (task != null && resource != null)
      {
         ResourceAssignment mpxjAssignment = task.addResourceAssignment(resource);
         mpxjAssignment.setUnits(gpAllocation.getLoad());
         m_eventManager.fireAssignmentReadEvent(mpxjAssignment);
      }
   }

   private Map<String, CustomProperty> getResourcePropertyTypes()
   {
      Map<String, CustomProperty> map = new HashMap<>();
      CustomProperty numeric = new CustomProperty(ResourceFieldLists.CUSTOM_NUMBER);
      map.put("int", numeric);
      map.put("double", numeric);
      map.put("text", new CustomProperty(ResourceFieldLists.CUSTOM_TEXT));
      map.put("date", new CustomProperty(ResourceFieldLists.CUSTOM_DATE));
      map.put("boolean", new CustomProperty(ResourceFieldLists.CUSTOM_FLAG));
      return map;
   }

   private Map<String, CustomProperty> getTaskPropertyTypes()
   {
      Map<String, CustomProperty> map = new HashMap<>();
      CustomProperty numeric = new CustomProperty(TaskFieldLists.CUSTOM_NUMBER);
      map.put("int", numeric);
      map.put("double", numeric);
      map.put("text", new CustomProperty(TaskFieldLists.CUSTOM_TEXT));
      map.put("date", new CustomProperty(TaskFieldLists.CUSTOM_DATE));
      map.put("boolean", new CustomProperty(TaskFieldLists.CUSTOM_FLAG));
      return map;
   }

   private ProjectFile m_projectFile;
   private ProjectCalendar m_mpxjCalendar;
   private EventManager m_eventManager;
   private DateFormat m_localeDateFormat;
   private DateFormat m_dateFormat;
   private Map<String, Pair<FieldType, Object>> m_resourcePropertyDefinitions;
   private Map<String, Pair<FieldType, Object>> m_taskPropertyDefinitions;
   private Map<String, String> m_roleDefinitions;
   private Map<String, CustomProperty> m_resourcePropertyTypes;
   private Map<String, CustomProperty> m_taskPropertyTypes;

   private static final int[] PRIORITY =
   {
      Priority.LOW, // 0 - Low
      Priority.MEDIUM, // 1 - Normal
      Priority.HIGH, // 2 - High
      Priority.LOWEST, // 3- Lowest
      Priority.HIGHEST, // 4 - Highest
   };

   static final RelationType[] RELATION =
   {
      null, //0
      RelationType.START_START, // 1 - Start Start
      RelationType.FINISH_START, // 2 - Finish Start
      RelationType.FINISH_FINISH, // 3 - Finish Finish
      RelationType.START_FINISH // 4 - Start Finish
   };

   /**
    * Cached context to minimise construction cost.
    */
   private static JAXBContext CONTEXT;

   /**
    * Note any error occurring during context construction.
    */
   private static JAXBException CONTEXT_EXCEPTION;

   static
   {
      try
      {
         //
         // JAXB RI property to speed up construction
         //
         System.setProperty("com.sun.xml.bind.v2.runtime.JAXBContextImpl.fastBoot", "true");

         //
         // Construct the context
         //
         CONTEXT = JAXBContext.newInstance("net.sf.mpxj.ganttproject.schema", GanttProjectReader.class.getClassLoader());
      }

      catch (JAXBException ex)
      {
         CONTEXT_EXCEPTION = ex;
         CONTEXT = null;
      }
   }
}
