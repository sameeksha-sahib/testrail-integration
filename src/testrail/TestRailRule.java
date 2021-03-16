package testrail;

import utils.PropertyUtils;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

public class TestRailRule {
    
    // Test rail url and credentials
    private static String RAILS_ENGINE_URL = PropertyUtils.getProperty("testrail.url");
    private static String TESTRAIL_USERNAME = PropertyUtils.getProperty("testrail.username");
    private static String TESTRAIL_PASSWORD = PropertyUtils.getProperty("testrail.password");
    private static String TESTRAIL_USER_ID = PropertyUtils.getProperty("testrail.userid");

    private static String current_date = java.util.Calendar.getInstance().getTime().toString();
    private static String TEST_RUN_ID;

    /**
     * Initialize Testrail APIClient with testrail credentials
     */
    public static APIClient setApiClient() {
        APIClient client = new APIClient(RAILS_ENGINE_URL);
        client.setUser(TESTRAIL_USERNAME);
        client.setPassword(TESTRAIL_PASSWORD);
        return client;
    }

    /**
     * Create new run in suite and add entry in the already created plan with PLAN_ID
     *
     * @param suiteID:      ID of the suite, in which plan entry needs to be added.
     * @param assignedtoID: ID of the testrail user, to whome run should be assigned
     * @param planID:       ID of plan in which entry is to be added
     */
    public static Long addPlanEntry(int suiteID, int assignedtoID, Long planID) throws IOException, APIException {
        APIClient client = setApiClient();

        Map<String, java.io.Serializable> data = new HashMap<String, java.io.Serializable>();
        data.put("suite_id", suiteID);
        data.put("include_all", true);
        data.put("assignedto_id", assignedtoID);
        data.put("name", current_date);

        JSONObject r = (JSONObject) client.sendPost("add_plan_entry/" + planID, data);
        JSONArray runs = (JSONArray) r.get("runs");
        JSONObject run0 = (JSONObject) runs.get(0);
        Long id = (Long) run0.get("id");

        TEST_RUN_ID = id.toString();
        return id;
    }

    /**
     * If the plan with name planTitle is not already created, create new test plan as copy of base test plan.
     *
     * @param projectID:  ID of test rail project
     * @param planTitle:  Name of the plan to be created
     * @param basePlanID: ID of the base test plan, whose copy need to be created
     * @return IDs of the child runs of the test plan
     * @throws Exception
     */
    public static ArrayList<Long> getRunIDs(String projectID, String planTitle, String basePlanID) throws Exception {

        APIClient client = setApiClient();
        ArrayList<Long> runIDs = new ArrayList<>();

        /*
         * status ID string is for all result statuses. String can be adjusted for specific test IDs.
         * See http://docs.gurock.com/testrail-api2/reference-statuses for additional information
         */
        String statuses = "1,2,3,4,5,6,7,8,9,10,11,12";
        JSONObject plan;
        String planID = isPlanAlreadyCreated(client, projectID, planTitle);
        if (planID.isEmpty())
            plan = rerunPlan(client, basePlanID, planTitle, statuses);
        else
            plan = getPlan(client, planID);

        // Exctract child run ids from the plan
        ArrayList entries = (ArrayList) plan.get("entries");
        for (Object object : entries) {
            JSONObject entry = (JSONObject) object;
            JSONArray runArr = (JSONArray) entry.get("runs");
            JSONObject run = (JSONObject) runArr.get(0);
            runIDs.add((Long) run.get("id"));
        }
        return runIDs;
    }

    /**
     * Create a new test plan using the same details as an existing plan in the project. The title of the old
     * plan is not replicated.
     *
     * @param client   The APIClient used to make the API requests. Should be initialized prior to calling
     *                 this function.
     * @param planID   Valid integer ID of an existing test plan in TestRail
     * @param title    Title of the new test plan to be created. Cannot be an empty string.
     * @param statuses Comma-separated string of integer status IDs in TestRail. These are the status IDs
     *                 of the existing tests which will be included in the new test run.
     * @return The response data from add_run. See http://docs.gurock.com/testrail-api2/reference-runs#add_plan
     * @throws APIException
     * @throws IOException
     */
    public static JSONObject rerunPlan(APIClient client, String planID, String title, String statuses) throws
            APIException, IOException {
        Map post_body = new HashMap();

        //Get the previous run details
        JSONObject run_details = getPlan(client, planID);

        long project_id = (Long) run_details.get("project_id");

        // Store plan's properties for the new test plan
        post_body.put("name", (String) title);
        post_body.put("description", (String) run_details.get("description"));

        ArrayList entries = (ArrayList) run_details.get("entries");
        for (Object object : entries) {
            JSONObject entry = (JSONObject) object;
            JSONArray runArr = (JSONArray) entry.get("runs");
            JSONObject run = (JSONObject) runArr.get(0);
            String runName = String.valueOf(run.get("name"));
            Long suiteID = (Long) entry.get("suite_id");

            // Get test case ids from previous runs in the base plan
            JSONArray case_ids = getCaseIDsForRun(client, (Long) run.get("id"), statuses);

            // Update entry
            entry.clear();
            entry.put("include_all", Boolean.FALSE);
            entry.put("case_ids", case_ids);
            entry.put("name", runName);
            entry.put("suite_id", suiteID);
        }
        post_body.put("entries", entries);
        return (JSONObject) client.sendPost("add_plan/" + project_id, post_body);
    }

    /**
     * Get details of the plan with planID
     *
     * @param client: The APIClient used to make the API requests. Should be initialized prior to calling
     *                this function.
     * @param planID: ID of the test plan
     * @return Details of the plan as JSONObject
     * @throws IOException
     * @throws APIException
     */
    public static JSONObject getPlan(APIClient client, String planID) throws IOException, APIException {
        return (JSONObject) client.sendGet("get_plan/" + planID);
    }

    /**
     * Gets a list of test case IDs which correspond to the tests from an existing test run.
     *
     * @param client   The APIClient used to make the API requests. Should be initialized prior to calling
     *                 this function.
     * @param runID    Valid integer ID of an existing test run in TestRail.
     * @param statuses Comma-separated string of integer status IDs in TestRail. These are the status IDs
     *                 of the existing tests which will be retrieved from the existing test run.
     * @return An array of test case IDs associated with tests in the test run
     * @throws APIException
     * @throws IOException
     */
    public static JSONArray getCaseIDsForRun(APIClient client, Long runID, String statuses) throws
            APIException, IOException {
        JSONArray response = (JSONArray) client.sendGet("get_tests/" + runID + "&status_id=" + statuses);
        return getValuesForKey("case_id", response);
    }

    /**
     * Checks an array of JSONObjects for all values corresponding to key_name.
     *
     * @param keyName The key for which values are sought
     * @param arr     The array containing JSONObjects from which the key/value pairs will be checked.
     *                This should contain values in which the key_name can be checked.
     * @return An array of Long values which correspond to the key parameter
     */
    public static JSONArray getValuesForKey(String keyName, JSONArray arr) {
        JSONArray value_list = new JSONArray();
        for (Object object : arr) {
            JSONObject entry = (JSONObject) object;
            value_list.add((Long) (entry.get(keyName)));
        }
        return value_list;
    }

    /**
     * Returns the id of the plan with name 'title' if it is already present in the project with id 'projectID'
     *
     * @param client:    The APIClient used to make the API requests. Should be initialized prior to calling
     *                   this function.
     * @param projectID: ID of the project
     * @param title:     Title of the test plan
     * @return ID of the plan if it's present in the project
     * @throws IOException
     * @throws APIException
     */
    public static String isPlanAlreadyCreated(APIClient client, String projectID, String title) throws IOException, APIException {
        JSONArray plans = (JSONArray) client.sendGet("get_plans/" + projectID + "&created_by=" + TESTRAIL_USER_ID);
        String planID = "";
        for (int i = 0; i < plans.size(); ++i) {
            JSONObject result = (JSONObject) plans.get(i);
            if (result.get("name").equals(title)) {
                planID = result.get("id").toString();
                break;
            }
        }
        return planID;
    }
}
