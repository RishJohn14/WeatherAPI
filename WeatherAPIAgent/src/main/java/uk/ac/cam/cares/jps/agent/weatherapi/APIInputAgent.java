import org.json.JSONArray;
import org.json.JSONObject;
import org.jooq.exception.DataAccessException;
import uk.ac.cam.cares.jps.base.util.JSONKeyToIRIMapper;
import uk.ac.cam.cares.jps.base.timeseries.TimeSeries;
import uk.ac.cam.cares.jps.base.timeseries.TimeSeriesClient;
import uk.ac.cam.cares.jps.base.timeseries.TimeSeriesSparql;


import uk.ac.cam.cares.jps.base.exception.JPSRuntimeException;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;
import java.util.zip.GZIPInputStream;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;


public class APIInputAgent
{
    public static final Logger Log = java.util.logging.LogManager.getLogManager(APIInputAgent.class);
    private TimeSeriesClient<OffsetDateTime> tsclient;
    private List<JSONKeyToIRIMapper> mappings;
    public static final String generatedIRIPrefix = TimeSeriesSparql.ns_kb + "WeatherStation";
    public static final String timeUnit = OffsetDateTime.class.getSimpleName();
    public static final String timestampkey = "obsTimeUtc";
    public static final String status = "qcstatus";
    public static final ZoneOffset ZONE_OFFSET = ZoneOffset.UTC;
     

    public APIInputAgent(String propertiesFile) throws IOException
    {
        try(InputStream input = new FileInputStream(propertiesFile))
        {
            Properties prop = new Properties();
            prop.load(input);
            String mappingFolder;

            try
            {
                mappingFolder = System.getenv(prop.getProperty("caresweatherstation.mappingfolder")); 
                //to substitute for values i need;
            }
            catch(NullPointerException e)
            {
                throw new IOException("The key caresweatherstation.mappingfolder cannot be found in the file");

            }

            if(mappingFolder==null)
            {
                throw new InvalidPropertiesFormatException("The properties file does not contain the key caresweatherstation.mappingfolder with a path to the folder containing the required JSON key to IRI Mappings");
            }

            readmappings(mappingFolder);
        }

    }

    public int getNumberofTimeSeries()
    {
        return mappings.size;
    }

    public void setTsClient(TimeSeriesClient<OffsetDateTime> tsclient)
    {
        this.tsclient = tsclient;
    }

    private void readmappings(String mappingfolder) throws IOException
    {
        mappings = new ArrayList<>();
        File folder = new File(mappingFolder);
        File[] mappingFiles = folder.listFiles();

        if(mappingFiles==null)
        {
            throw new IOException("Folder does not exist: " + mappingfolder);
        }
        if(mappingFiles.length==0)
        {
            throw new IOException("No files in folder");

        }
        else
        {
            for( File mappingFile: mappingFiles)
            {
                JSONKeyToIRIMapper mapper = new JSONKeyToIRIMapper(APIInputAgent.generatedIRIPrefix, mappingFile.getAbsolutePath());
                mappings.add(mapper);
                mapper.saveToFile(mappingFile.getAbsolutePath());
            }
        }
    }

    public void initializeTimeSeriesIfNotExist()
    {
        for(JSONKeyToIRIMapper mapping:mappings)
        {
            List<String> iris = mapping.getAllIRIs();
            if(!timeSeriesExist(iris))
            {
                List<Class<?>> classes = iris.stream().map(this::getClassfromJSONKey).collect(Collectors.toList());
                // TO clarify later on Google.
                
                try
                {
                    tsclient.initTimeSeries(iris,classes,timeUnit);
                    Log.info(String.format("Initialized time series with the following IRIs: %s", String.join(", ", iris)));
                
                }
                catch(Exception e)
                {
                    throw new JPSRunTimeException("Could not instantiate TimeSeries");
                }
            }
        }
    }
    private boolean timeSeriesExist(List<String> iris)
    {
        for (String iri:iris)
        {
            try
            {
                if(!tsclient.checkDataHasTimeSeries(iri))
                {
                    return false;
                }
            }
            catch(DataAccessException e)
            {
                if (e.getMessage().contains("ERROR: relation \"dbTable\" does not exist")) 
                {
                    return false;
                }
                else 
                {
                    throw e;
                }
            }
        }
    }

    public void updateData(JSONObject weatherReadings) throws IllegalArgumentException
    {
        Map <String, List<?>> weatherReadingsMap = new HashMap<>();
        try
        {
            weatherReadingsMap = jsonObjectToMap(weatherReadings);
        }
        catch (Exception e)
        {
            throw new JPSRunTimeException ("Readings cannot be empty");
        }


        if(!weatherReadings.isEmpty())
        {
            List<TimeSeries<OffsetDateTime>> timeSeries;
            try
            {
                timeSeries = convertReadingsToTimeSeries(weatherReadingsMap);
            }
            catch (NoSuchElementException e)
            {
                throw new IllegalArgumentException("Readings cannot be converted to ProperTimeSeries",e);
            }
            for (TimeSeries<OffsetDateTime> ts : timeSeries) 
            {
                // Retrieve current maximum time to avoid duplicate entries (can be null if no data is in the database yet)
                OffsetDateTime endDataTime;
                try 
                 {
                	endDataTime= tsClient.getMaxTime(ts.getDataIRIs().get(0));
                 } 
                 catch (Exception e) 
                 {
                	throw new JPSRuntimeException("Could not get max time!");
                  }
                OffsetDateTime startCurrentTime = ts.getTimes().get(0);
                // If there is already a maximum time
                if (endDataTime != null) 
                {
                    // If the new data overlaps with existing timestamps, prune the new ones
                    if (startCurrentTime.isBefore(endDataTime))
                        ts = pruneTimeSeries(ts, endDataTime);
                }
                // Only update if there actually is data
                if (!ts.getTimes().isEmpty()) 
                {
                	try 
                    {
                      tsClient.addTimeSeriesData(ts);
                      Log.debug(String.format("Time series updated for following IRIs: %s", String.join(", ", ts.getDataIRIs())));
                    }
                    catch (Exception e)
                    {
                	   throw new JPSRuntimeException("Could not add timeseries!");
                    } 
                }
            }
        }
        else 
        {
            throw new IllegalArgumentException("Readings can not be empty!");
        }
    }

    private Map<String, List<?>> jsonObjectToMap(JSONObject readings) {

        // First save the values as Object //
        Map<String, List<Object>> readingsMap = new HashMap<>();
        JSONArray jsArr;
        try {
            jsArr = readings.getJSONArray("observations");
            for(int i=0; i<jsArr.length();i++) {
                JSONObject currentEntry = jsArr.getJSONObject(i);
                Iterator<String> it = currentEntry.keys();
                while(it.hasNext()) {
                    String key = it.next();
                    Object value = currentEntry.get(key);
                    if (value.getClass() != JSONObject.class) {
                        // Get the value and add it to the corresponding list
                        // Handle cases where the API returned null
                        if (value == JSONObject.NULL) {
                            // Handling depends on the datatype of the current key
                            String datatype = getClassFromJSONKey(key).getSimpleName();
                            // If it is a number use NaN (not a number)
                            if (datatype.equals(Integer.class.getSimpleName()) || datatype.equals(Double.class.getSimpleName()) || datatype.equals(Long.class.getSimpleName())) {
                                value = Double.NaN;
                            }
                            // Otherwise, use the string NA (not available)
                            else {
                                value = "NA";
                            }
                        }
                        // If the key is not present yet initialize the list
                        if (!readingsMap.containsKey(key)) {
                            readingsMap.put(key, new ArrayList<>());
                        }
                        if (key.equals("metric_si"))
                            Log.info(String.format("Reading %s key now", key));
                        readingsMap.get(key).add(value);
                    } 
                    else 
                    {
                        JSONObject obj = currentEntry.getJSONObject("metric_si");
                        for (Iterator<String> it1 = obj.keys(); it1.hasNext(); ) {
                            String key1 = it1.next();
                            Object value1 = obj.get(key1);
                            // Handle cases where the API returned null
                            if (value1 == JSONObject.NULL) {
                                value1 = Double.NaN;
                            }
                            // If the key is not present yet initialize the list
                            if (!readingsMap.containsKey(key1)) {
                                readingsMap.put(key1, new ArrayList<>());
                            }
                            readingsMap.get(key1).add(value1);
                        }
                    }
                }
            }
        } catch (Exception e) {
            throw new JPSRuntimeException("Readings can not be empty!", e);
        }   

        // Convert the values to the proper datatype //
        Map<String, List<?>> readingsMapTyped = new HashMap<>();
        for(String key : readingsMap.keySet()) {
            // Get the class (datatype) corresponding to the key
            String datatype = getClassFromJSONKey(key).getSimpleName();
            // Get current list with object type
            List<Object> valuesUntyped = readingsMap.get(key);
            List<?> valuesTyped;
            // Use mapping to cast the values into integer, double, long or string
            // The Number cast is required for org.json datatypes
            if (datatype.equals(Integer.class.getSimpleName())) {
                valuesTyped = valuesUntyped.stream().map(x -> ((Number) x).intValue()).collect(Collectors.toList());
            } else if (datatype.equals(Double.class.getSimpleName())) {
                valuesTyped = valuesUntyped.stream().map(x -> ((Number) x).doubleValue()).collect(Collectors.toList());
            } else if (datatype.equals(Long.class.getSimpleName())) {
                valuesTyped = valuesUntyped.stream().map(x -> ((Number) x).longValue()).collect(Collectors.toList());
            } else {
                valuesTyped = valuesUntyped.stream().map(Object::toString).collect(Collectors.toList());
            }
            readingsMapTyped.put(key, valuesTyped);
        }
        return readingsMapTyped;

    }







    private List<TimeSeries<OffsetDateTime>> convertReadingsToTimeSeries(Map<String, List<?>> weatherReadings)
    throws  NoSuchElementException 
    {
       // Extract the timestamps by mapping the private conversion method on the list items
       // that are supposed to be string (toString() is necessary as the map contains lists of different types)

       List<OffsetDateTime> weatherTimestamps = weatherReadings.get(APIInputAgent.timestampKey).stream().map(timestamp -> (convertStringToOffsetDateTime(timestamp.toString()))).collect(Collectors.toList());

       // Construct a time series object for each mapping
       List<TimeSeries<OffsetDateTime>> timeSeries = new ArrayList<>();
       for (JSONKeyToIRIMapper mapping: mappings)
      {
          // Initialize the list of IRIs
            List<String> iris = new ArrayList<>();
           // Initialize the list of list of values
            List<List<?>> values = new ArrayList<>();
           for(String key: mapping.getAllJSONKeys()) 
            {
                // Add IRI
               iris.add(mapping.getIRI(key));
               if (weatherReadings.containsKey(key)) 
                {
                  values.add(weatherReadings.get(key));
                }
                else 
                {
                 throw new NoSuchElementException("The key " + key + " is not contained in the readings!");
                }
            }

          List<OffsetDateTime> times = weatherTimestamps;
          // Create the time series object and add it to the list
   
         TimeSeries<OffsetDateTime> currentTimeSeries = new TimeSeries<>(times, iris, values);
         timeSeries.add(currentTimeSeries);
      }

     return timeSeries;
   }
   private OffsetDateTime convertStringToOffsetDateTime(String timestamp)  
   {
     timestamp=timestamp.replace("Z","");

     DateTimeFormatter dtf=DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");
     LocalDateTime localTime=LocalDateTime.parse(timestamp,dtf);


     // Then add the zone id
     return OffsetDateTime.of(localTime, APIInputAgent.ZONE_OFFSET);
   }






   private TimeSeries<OffsetDateTime> pruneTimeSeries(TimeSeries<OffsetDateTime> timeSeries, OffsetDateTime timeThreshold) 
   {
     // Find the index from which to start
     List<OffsetDateTime> times = timeSeries.getTimes();
     int index = 0;
     while(index < times.size()) 
     {
        if (times.get(index).isAfter(timeThreshold)) 
        {
            break;
        }
        index++;
     }
     // Prune timestamps
     List<OffsetDateTime> newTimes = new ArrayList<>();
     // There are timestamps above the threshold
     if (index != times.size()) 
      {
         // Prune the times
         newTimes = new ArrayList<>(times.subList(index, times.size()));
      }
     // Prune data
     List<List<?>> newValues = new ArrayList<>();
     // Prune the values
     for (String iri: timeSeries.getDataIRIs())
       {
         // There are timestamps above the threshold
         if (index != times.size()) 
          {
             newValues.add(timeSeries.getValues(iri).subList(index, times.size()));
          }
         else 
          {
             newValues.add(new ArrayList<>());
          }
       }
     return new TimeSeries<>(newTimes, timeSeries.getDataIRIs(), newValues); 
    }





    private Class<?> getClassFromJSONKey(String jsonKey) {
        if (jsonKey.contains(timestampkey) || jsonKey.contains("stationID") || jsonKey.contains("obsTimeLocal") || jsonKey.contains("tz")){
            return String.class;
        }
        else if( jsonKey.contains(status) || jsonKey.contains("winddirAvg")){
            return Integer.class;
        }
        else if(jsonKey.equals("epoch")){
            return Long.class;
        }
        else{
            return Double.class;
        }
    }


}


