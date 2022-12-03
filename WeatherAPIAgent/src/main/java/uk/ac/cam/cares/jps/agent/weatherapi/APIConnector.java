//package uk.ac.cam.cares.jps.agent.WeatherAPI;

import org.apache.http.client.HttpResponseException;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.json.JSONException;
import org.json.JSONObject;
import uk.ac.cam.cares.jps.base.exception.JPSRuntimeException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;


import java.io.*;
import java.util.Properties;

import javax.print.attribute.standard.JobHoldUntil;



public class APIConnector
{
    private String API_URL = "https://api.data.gov.sg/";
    private String date;
    
    private static final String ERRORMSG = "Weather data could not be retrieved";
    private static final Logger LOG = java.util.logging.LogManager.getLogManager(APIAgentLauncher.class);

    //Standard Constructor to initialise the instance variables
    public APIConnector(String URL, String d)
    {
        API_URL=URL;
        date = d;
    }

    //Constructor to initialise the variables according to the Properties file

    public APIConnector(String filepath) throws IOException
    {
        loadAPIConfigs(filepath);
    }

    // Obtains Weather data in JSON format containing key:value pairs

    public JSONObject getWeatherReadings()
    {
        try{
            return retrieveWeatherData();

        }
        catch(IOException e)
        {
            LOG.error(ERRORMSG);
            throw new JPSRuntimeException(ERRORMSG,e);
        }
    }

    private JSONObject retrieveWeatherData() throws IOException, JSONException
    {
        //https://api.data.gov.sg/v1/environment/24-hour-weather-forecast?date=2022-11-24
        //https://api.data.gov.sg/v1/environment/24-hour-weather-forecast?date_time=2022-11-29T18%3A00%3A00

        
        String path = API_URL+"v1/environment/24-hour-weather-forecast?date_time="+date;

        try ( CloseableHttpClient httpclient =  HttpClients.createDefault())
        {
            HttpGet readrequest = new HttpGet(path);
            try ( CloseableHttpResponse response = httpclient.execute(readrequest))
            {
                int status = response.getStatusLine().getStatusCode();

                if(status==200) 
                {
                    return new JSONObject(EntityUtils.toString(response.getEntity()));

                }
                else
                {
                    throw new HttpResponseException(status,"Data could not be retrieved due to a server error");
                }

            }

        }

    }

    private void loadAPIConfigs(String filepath) throws IOException
    {
        File file = new File(filepath);
        if(!file.exists())
        {
            throw new FileNotFoundException("There was no file found in the path");
        }
        
        try (InputStream input = new FileInputStream(file))
        {
            Properties prop = new Properties();
            prop.load(input);

            if(prop.contains("weather.api_url"))
            {
                API_URL = prop.getProperty("weather.api_url");
            }
            else
            {
                throw new IOException("The file is missing: \"weather.api_url=<api_url>\"");
            }

            LocalDateTime current = LocalDateTime.now();
            DateTimeFormatter dtf = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");  
        
            String t = current.format(dtf);
            int size = t.length();
            String fin="";
            for(int i=0;i<size;i++)
            {
              char c = t.charAt(i);
              if(i==10)
              {
                fin = fin + "T";
              }
              else if (i==13||i==16)
              {
               fin = fin + "%3A";
              }
               else
               {
                fin = fin + c; 
               }        
            }

            date = fin;

        }
    }

}
