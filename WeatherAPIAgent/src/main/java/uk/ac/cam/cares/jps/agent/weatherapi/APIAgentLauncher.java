import org.json.JSONObject;

import netscape.javascript.JSObject;
import uk.ac.cam.cares.jps.base.exception.JPSRuntimeException;
import uk.ac.cam.cares.jps.base.timeseries.TimeSeriesClient;
import uk.ac.cam.cares.jps.base.agent.JPSAgent;
import java.util.*;
import java.io.IOException;
import java.time.OffsetDateTime;
import java.time.chrono.JapaneseChronology;

import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.BadRequestException;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

@WebServlet(urlPatterns = {"/retrieve"})

public class APIAgentLauncher extends JPSAgent
{
    public static final String Key_AgentProp = "agentProperties";
    public static final String Key_APIProp = "APIProperties";
    public static final String Key_ClientProp = "ClientProperties";
    
    private static final Logger Log = java.util.logging.LogManager.getLogManager(APIAgentLauncher.class);


    private static final String ARGUMENT_MISMATCH_MSG = "Need three properties files in the following order: 1) input agent 2) time series client 3) API connector.";
    private static final String AGENT_ERROR_MSG = "The CARESWeatherStation input agent could not be constructed!";
    private static final String TSCLIENT_ERROR_MSG = "Could not construct the time series client needed by the input agent!";
    private static final String INITIALIZE_ERROR_MSG = "Could not initialize time series.";
    private static final String CONNECTOR_ERROR_MSG = "Could not construct the CARES weather station API connector needed to interact with the API!";
    private static final String GET_READINGS_ERROR_MSG = "Some readings could not be retrieved.";

    public JSONObject processRequestParameters(JSONObject requestparams, HttpServletRequest request)
    {
        processRequestParameters(requestparams);
    }
    
    public JSONObject processRequestParameters(JSONObject requestparams)
    {
        JSONObject jsonMessage = new JSONObject();

        if(validateInput(requestparams))
        {   
            Log.info("Passing Request to API Input Agent");
            String agentProperties = System.getenv(requestParams.getString(Key_AgentProp));
            String clientProperties = System.getenv(requestParams.getString(Key_ClientProp));
            String apiProperties = System.getenv(requestParams.getString(Key_APIProp));
            
            String[] args = new String []{agentProperties,clientProperties,apiProperties};
            jsonMessage = initializeAgent(args);
            jsonMessage.accumulate("Result","TimeSeries has been updated");

            requestparams = jsonMessage;

        }
        else
        {
            jsonMessage.put("Result","Request Parameters not defined correctly");
            requestparams=jsonMessage;
        }
        return requestparams;
    }
    

    public boolean validateInput(JSONObject requestparams) throws BadRequestException
    {
        boolean validate = true;
        String agentProperties;
        String clientProperties;
        String apiproperties;
        if(requestparams.isempty())
         return false;

        else
        {
            validate = requestparams.has(Key_AgentProp);
            if(validate)
             validate = requestparams.has(Key_ClientProp);
            if(validate)
             validate = requestparams.has(Key_APIProp);
            if(validate)
            {
                agentProperties = (requestparams.getString(Key_AgentProp));
                clientProperties = (requestparams.getString(Key_ClientProp));
                apiproperties = (requestparams.getString(Key_APIProp));

                if((System.getenv(agentProperties)==null) || (System.getenv(clientProperties)==null) || (System.getenv(apiproperties)==null))
                 validate=false;

            }
        }

        return validate;
    }

    public static JSONObject initializeAgent(String []args)

    {
        if(args.length!=3)
        {
            Log.error(ARGUMENT_MISMATCH_MSG);
            throw new JPSRuntimeException(ARGUMENT_MISMATCH_MSG);
        }

        Log.debug("Launcher called with the following files: " + String.join(" ",args));

        APIInputAgent agent;
        try
        {
            agent = new APIInputAgent(args[0]);

        }
        catch(IOException e)
        {
            Log.error(AGENT_ERROR_MSG,e);
            throw new JPSRuntimeException(AGENT_ERROR_MSG,e);
        }

        Log.info("Input Agent object initialized");
        JSONObject jsonMessage = new JSONObject();
        jsonMessage.accumulate("Result","Input Agent Object Initialized");

        TimeSeriesClient<OffsetDateTime> tsclient;
        try
        {
            tsclient = new TimeSeriesClient<>(OffsetDateTime.class, args[1]);
            agent.setTsClient(tsclient);
        }
        catch(IOException e)
        {
            Log.error(TSCLIENT_ERROR_MSG,e);
            throw new JPSRuntimeException(TSCLIENT_ERROR_MSG, e); 
        }

        Log.info("Time Series object initialized");
        jsonMessage.accumulate("Result","Time Series Client Object Initialized");

        try
        {
            agent.initializeTimeSeriesIfNotExist();
        }
        catch(JPSRuntimeException e)
        {
            Log.error(INITIALIZE_ERROR_MSG);
            throw new JPSRuntimeException(INITIALIZE_ERROR_MSG,e);
        }

        APIConnector connector;
        try
        {
            connector = new APIConnector(args[2]);
        }
        catch(IOException e)
        {
            log.error(CONNECTOR_ERROR_MSG,e);
            throw new JPSRunTimeException(CONNECTOR_ERROR_MSG,e);
        }

        Log.info("API Connector Object Initialized");
        jsonMessage.accumulate("Result","API Connector object Initialized");

        JSONObject weatherReadings;

        try
        {
            weatherReadings = connector.getWeatherReadings();
        }
        catch(Exception e)
        {
            Log.error(GET_READINGS_ERROR_MSG,e);
            throw new JPSRunTimeException(GET_READINGS_ERROR_MSG,e);
        }

        Log.info(String.format("Retrieved %d weather readings", weatherReadings.length()));
        jsonMessage.accumulate("Result","Retrieved"+weatherReadings.getJSONArray("Observations").length+" station readings");

        if(!weatherReadings.isEmpty())
        {
            agent.updateData(weatherReadings);
            Log.info("Data updated with new API Readings");
            jsonMessage.accumulate("Result","Data updated with new API Readings");

        }
        else if(weatherReadings.isEmpty())
        {
            Log.info("No new weather data recorded");
            jsonMessage.accumulate("Result","No new weather data recorded");
        }
       return jsonMessage;
    }
}
