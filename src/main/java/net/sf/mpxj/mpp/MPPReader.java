/*
 * file:       MPPReader.java
 * author:     Jon Iles
 * copyright:  (c) Packwood Software 2005
 * date:       2005-12-21
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

package net.sf.mpxj.mpp;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.sf.mpxj.CalendarType;
import net.sf.mpxj.ProjectCalendar;
import net.sf.mpxj.Resource;
import org.apache.poi.poifs.filesystem.DirectoryEntry;
import org.apache.poi.poifs.filesystem.DocumentEntry;
import org.apache.poi.poifs.filesystem.DocumentInputStream;
import org.apache.poi.poifs.filesystem.POIFSFileSystem;

import net.sf.mpxj.DateRange;
import net.sf.mpxj.MPXJException;
import net.sf.mpxj.ProjectConfig;
import net.sf.mpxj.ProjectFile;
import net.sf.mpxj.ProjectProperties;
import net.sf.mpxj.Relation;
import net.sf.mpxj.Task;
import net.sf.mpxj.reader.AbstractProjectStreamReader;

/**
 * This class creates a new ProjectFile instance by reading an MPP file.
 */
public final class MPPReader extends AbstractProjectStreamReader
{
   @Override public ProjectFile read(InputStream is) throws MPXJException
   {
      try
      {
         //
         // Open the file system
         //
         POIFSFileSystem fs = new POIFSFileSystem(is);

         return read(fs);

      }
      catch (IOException ex)
      {

         throw new MPXJException(MPXJException.READ_ERROR, ex);

      }
   }

   @Override public List<ProjectFile> readAll(InputStream inputStream) throws MPXJException
   {
      return Collections.singletonList(read(inputStream));
   }

   /**
    * This method allows us to peek into the OLE compound document to extract the file format.
    * This allows the UniversalProjectReader to determine if this is an MPP file, or if
    * it is another type of OLE compound document.
    *
    * @param fs POIFSFileSystem instance
    * @return file format name
    */
   public static String getFileFormat(POIFSFileSystem fs) throws IOException
   {
      String fileFormat = "";
      DirectoryEntry root = fs.getRoot();
      if (root.getEntryNames().contains("\1CompObj"))
      {
         CompObj compObj = new CompObj(new DocumentInputStream((DocumentEntry) root.getEntry("\1CompObj")));
         fileFormat = compObj.getFileFormat();
      }
      return fileFormat;
   }

   /**
    * Alternative entry point allowing an MPP file to be read from
    * a user-supplied POI file stream.
    *
    * @param fs POI file stream
    * @return ProjectFile instance
    */
   public ProjectFile read(POIFSFileSystem fs) throws MPXJException
   {
      try
      {
         ProjectFile projectFile = new ProjectFile();
         ProjectConfig config = projectFile.getProjectConfig();

         config.setAutoTaskID(false);
         config.setAutoTaskUniqueID(false);
         config.setAutoResourceID(false);
         config.setAutoResourceUniqueID(false);
         config.setAutoOutlineLevel(false);
         config.setAutoOutlineNumber(false);
         config.setAutoWBS(false);
         config.setAutoCalendarUniqueID(false);
         config.setAutoAssignmentUniqueID(false);

         addListenersToProject(projectFile);

         //
         // Open the file system and retrieve the root directory
         //
         DirectoryEntry root = fs.getRoot();

         //
         // Retrieve the CompObj data, validate the file format and process
         //
         CompObj compObj = new CompObj(new DocumentInputStream((DocumentEntry) root.getEntry("\1CompObj")));
         ProjectProperties projectProperties = projectFile.getProjectProperties();
         projectProperties.setFullApplicationName(compObj.getApplicationName());
         projectProperties.setApplicationVersion(compObj.getApplicationVersion());
         String format = compObj.getFileFormat();
         Class<? extends MPPVariantReader> readerClass = FILE_CLASS_MAP.get(format);
         if (readerClass == null)
         {
            throw new MPXJException(MPXJException.INVALID_FILE + ": " + format);
         }
         MPPVariantReader reader = readerClass.newInstance();
         reader.process(this, projectFile, root);

         //
         // Update the internal structure. We'll take this opportunity to
         // generate outline numbers for the tasks as they don't appear to
         // be present in the MPP file.
         //
         config.setAutoOutlineNumber(true);
         projectFile.updateStructure();
         config.setAutoOutlineNumber(false);

         //
         // Perform post-processing to set the summary flag and clean
         // up any instances where a task has an empty splits list.
         //
         for (Task task : projectFile.getTasks())
         {
            task.setSummary(task.hasChildTasks());
            List<DateRange> splits = task.getSplits();
            if (splits != null && splits.isEmpty())
            {
               task.setSplits(null);
            }
            validationRelations(task);
         }

         //
         // Prune unused resource calendars
         //
         projectFile.getCalendars().removeIf(c -> c.isDerived() && c.getResourceCount() == 0);

         //
         // Resource calendar post processing
         //
         for (Resource resource : projectFile.getResources())
         {
            ProjectCalendar calendar = resource.getCalendar();
            if (calendar != null)
            {
               // Configure the calendar type
               if (calendar.isDerived())
               {
                  calendar.setType(CalendarType.RESOURCE);
                  calendar.setPersonal(calendar.getResourceCount() == 1);
               }

               // Resource calendars without names inherit the resource name
               if (calendar.getName() == null || calendar.getName().isEmpty())
               {
                  String name = resource.getName();
                  if (name == null || name.isEmpty())
                  {
                     name = "Unnamed Resource";
                  }
                  calendar.setName(name);
               }
            }
         }

         //
         // Ensure that the unique ID counters are correct
         //
         config.updateUniqueCounters();

         //
         // Add some analytics
         //
         String projectFilePath = projectFile.getProjectProperties().getProjectFilePath();
         if (projectFilePath != null && projectFilePath.startsWith("<>\\"))
         {
            projectProperties.setFileApplication("Microsoft Project Server");
         }
         else
         {
            projectProperties.setFileApplication("Microsoft");
         }
         projectProperties.setFileType("MPP");

         return (projectFile);
      }

      catch (IOException | InstantiationException | IllegalAccessException ex)
      {
         throw new MPXJException(MPXJException.READ_ERROR, ex);
      }
   }

   /**
    * This method validates all relationships for a task, removing
    * any which have been incorrectly read from the MPP file and
    * point to a parent task.
    *
    * @param task task under test
    */
   private void validationRelations(Task task)
   {
      List<Relation> predecessors = task.getPredecessors();
      if (!predecessors.isEmpty())
      {
         ArrayList<Relation> invalid = new ArrayList<>();
         for (Relation relation : predecessors)
         {
            Task sourceTask = relation.getSourceTask();
            Task targetTask = relation.getTargetTask();

            String sourceOutlineNumber = sourceTask.getOutlineNumber();
            String targetOutlineNumber = targetTask.getOutlineNumber();

            if (sourceOutlineNumber != null && targetOutlineNumber != null && sourceOutlineNumber.startsWith(targetOutlineNumber + '.'))
            {
               invalid.add(relation);
            }
         }

         for (Relation relation : invalid)
         {
            relation.getSourceTask().removePredecessor(relation.getTargetTask(), relation.getType(), relation.getLag());
         }
      }
   }

   /**
    * If this flag is true, raw timephased data will be retrieved
    * from MS Project: no normalisation will take place.
    *
    * @return boolean flag
    */
   public boolean getUseRawTimephasedData()
   {
      return m_useRawTimephasedData;
   }

   /**
    * If this flag is true, raw timephased data will be retrieved
    * from MS Project: no normalisation will take place.
    *
    * @param useRawTimephasedData boolean flag
    */
   public void setUseRawTimephasedData(boolean useRawTimephasedData)
   {
      m_useRawTimephasedData = useRawTimephasedData;
   }

   /**
    * Retrieves a flag which indicates whether presentation data will
    * be read from the MPP file. Not reading this data saves time and memory.
    *
    * @return presentation data flag
    */
   public boolean getReadPresentationData()
   {
      return m_readPresentationData;
   }

   /**
    * Flag to allow time and memory to be saved by not reading
    * presentation data from the MPP file.
    *
    * @param readPresentationData set to false to prevent presentation data being read
    */
   public void setReadPresentationData(boolean readPresentationData)
   {
      m_readPresentationData = readPresentationData;
   }

   /**
    * Flag to determine if the reader should only read the project properties.
    * This allows for rapid access to the document properties, without the
    * cost of reading the entire contents of the project file.
    *
    * @return true if the reader should only read the project properties
    */
   public boolean getReadPropertiesOnly()
   {
      return m_readPropertiesOnly;
   }

   /**
    * Flag to determine if the reader should only read the project properties.
    * This allows for rapid access to the document properties, without the
    * cost of reading the entire contents of the project file.
    *
    * @param readPropertiesOnly true if the reader should only read the project properties
    */
   public void setReadPropertiesOnly(boolean readPropertiesOnly)
   {
      m_readPropertiesOnly = readPropertiesOnly;
   }

   /**
    * Set the read password for this Project file. This is needed in order to
    * be allowed to read a read-protected Project file.
    *
    * Note: Set this each time before calling the read method.
    *
    * @param password password text
    */
   public void setReadPassword(String password)
   {
      m_readPassword = password;
   }

   /**
    * Internal only. Get the read password for this Project file. This is
    * needed in order to be allowed to read a read-protected Project file.
    *
    * @return password password text
    */
   public String getReadPassword()
   {
      return m_readPassword;
   }

   /**
    * Where supported, set to false to ignore password protection.
    *
    * @param respectPasswordProtection true if password protection is respected
    */
   public void setRespectPasswordProtection(boolean respectPasswordProtection)
   {
      m_respectPasswordProtection = respectPasswordProtection;
   }

   /**
    * Retrieve a flag indicating if password protection is respected.
    *
    * @return true if password protection is respected
    */
   boolean getRespectPasswordProtection()
   {
      return m_respectPasswordProtection;
   }

   /**
    * Setting this flag to true allows raw timephased data to be retrieved.
    */
   private boolean m_useRawTimephasedData;

   /**
    * Flag to allow time and memory to be saved by not reading
    * presentation data from the MPP file.
    */
   private boolean m_readPresentationData = true;
   private boolean m_readPropertiesOnly;

   /**
    * Where supported, set to false to ignore password protection.
    */
   private boolean m_respectPasswordProtection = true;

   private String m_readPassword;

   /**
    * Populate a map of file types and file processing classes.
    */
   private static final Map<String, Class<? extends MPPVariantReader>> FILE_CLASS_MAP = new HashMap<>();
   static
   {
      FILE_CLASS_MAP.put("MSProject.MPP9", MPP9Reader.class);
      FILE_CLASS_MAP.put("MSProject.MPT9", MPP9Reader.class);
      FILE_CLASS_MAP.put("MSProject.GLOBAL9", MPP9Reader.class);
      FILE_CLASS_MAP.put("MSProject.MPP8", MPP8Reader.class);
      FILE_CLASS_MAP.put("MSProject.MPT8", MPP8Reader.class);
      FILE_CLASS_MAP.put("MSProject.MPP12", MPP12Reader.class);
      FILE_CLASS_MAP.put("MSProject.MPT12", MPP12Reader.class);
      FILE_CLASS_MAP.put("MSProject.GLOBAL12", MPP12Reader.class);
      FILE_CLASS_MAP.put("MSProject.MPP14", MPP14Reader.class);
      FILE_CLASS_MAP.put("MSProject.MPT14", MPP14Reader.class);
      FILE_CLASS_MAP.put("MSProject.GLOBAL14", MPP14Reader.class);
   }
}
