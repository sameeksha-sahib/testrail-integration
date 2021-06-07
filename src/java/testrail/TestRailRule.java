package testrail;

import hooks.TestRailHook;
import utils.PropertyUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

public class TestRailRule {
    private static final Logger logger = LogManager.getLogger(TestRailHook.class);

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
        logger.info("TestRail API Client created");
        return client;
    }

    /**
     * Create new run in suite and add entry in the already created plan with PLAN_ID, refer https://www.gurock.com/testrail/docs/api/reference/plans
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
    public static ArrayList<Long> getRunIDs(String projectID, String planTitle, String basePlanID, String desiredRunIDs) throws Exception {

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
            plan = rerunPlan(client, basePlanID, planTitle, statuses, desiredRunIDs);
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
    public static JSONObject rerunPlan(APIClient client, String planID, String title, String statuses, String desiredRunIDs) throws
            APIException, IOException {
        Map post_body = new HashMap();

        //Get the previous run details
        JSONObject run_details = getPlan(client, planID);

        long project_id = (Long) run_details.get("project_id");

        // Store plan's properties for the new test plan
        post_body.put("name", (String) title);
        post_body.put("description", (String) run_details.get("description"));

        ArrayList entries = (ArrayList) run_details.get("entries");
        ArrayList requiredEntries = new ArrayList();
        for (Object object : entries) {
            JSONObject entry = (JSONObject) object;
            JSONArray runArr = (JSONArray) entry.get("runs");
            JSONObject run = (JSONObject) runArr.get(0);
            String runName = String.valueOf(run.get("name"));
            if (desiredRunIDs.isEmpty() || desiredRunIDs.contains(runName)) {
                Long suiteID = (Long) entry.get("suite_id");

                // Get test case ids from previous runs in the base plan
                JSONArray case_ids = getCaseIDsForRun(client, (Long) run.get("id"), statuses);

                // Update entry
                entry.clear();
                entry.put("include_all", Boolean.FALSE);
                entry.put("case_ids", case_ids);
                entry.put("name", runName);
                entry.put("suite_id", suiteID);
                requiredEntries.add(entry);
            }
        }
        post_body.put("entries", requiredEntries);
        return (JSONObject) client.sendPost("add_plan/" + project_id, post_body);
    }

    /**
     * Get details of the plan with planID, refer https://www.gurock.com/testrail/docs/api/reference/plans
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
     * Gets a list of test case IDs which correspond to the tests from an existing test run, refer https://www.gurock.com/testrail/docs/api/reference/tests
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

    /**
     * Creates backup of suite by creating its copy with test cases in correct section heirarchy
     *
     * @param client:     The APIClient used to make the API requests. Should be initialized prior to calling
     *                    this function.
     * @param projectID:  ID of the project
     * @param suiteID:    ID of suite whose backup is to be taken
     * @param backupName: Name of the new backup created
     * @return ID of the new suite created
     * @throws IOException
     * @throws APIException
     */
    public static long createBackupOfSuite(APIClient client, long projectID, long suiteID, String backupName) throws IOException, APIException {

        // Get the previous suite details
        JSONObject suiteDetails = getSuite(client, suiteID);

        // Create new suite
        JSONObject newSuiteDetails = addSuite(client, projectID, suiteDetails.get("name") + "-" + backupName, (String) suiteDetails.get("description"));
        long newSuiteID = (Long) newSuiteDetails.get("id");
        logger.info("NEW Suite ID for " + suiteDetails.get("name") + ": " + newSuiteID);

        // Get sections of previouse suite, and add them to new suite
        JSONArray sections = (JSONArray) getSections(client, projectID, suiteID);
        HashMap<Long, Long> sectionIDs = new HashMap<>();
        for (int i = 0; i < sections.size(); ++i) {
            JSONObject section = (JSONObject) sections.get(i);
            long sectionID = (long) section.get("id");

            // Get test cases of section
            JSONArray testCases = (JSONArray) getCases(client, projectID, suiteID, sectionID);

            // Create section in new suite with correct hierarchy
            JSONObject newSection;
            if (section.get("parent_id") == null) {
                newSection = addSection(client, projectID, newSuiteID, (String) section.get("name"), null);
            } else {
                newSection = addSection(client, projectID, newSuiteID, (String) section.get("name"), sectionIDs.get(section.get("parent_id")));
            }

            // Copy test cases if present
            if (testCases.size() > 0)
                copyCasesToSection(client, (Long) newSection.get("id"), getCaseIDs(testCases));

            // Store section ids
            sectionIDs.put((Long) sectionID, (Long) newSection.get("id"));
        }
        return newSuiteID;
    }

    /**
     * Returns the details of the suite, refer https://www.gurock.com/testrail/docs/api/reference/suites
     *
     * @param client:  The APIClient used to make the API requests. Should be initialized prior to calling
     *                 this function.
     * @param suiteID: ID of suite whoese details are required
     * @return Response of API as JSONObject: Details of suite
     * @throws IOException
     * @throws APIException
     */
    public static JSONObject getSuite(APIClient client, long suiteID) throws IOException, APIException {
        return (JSONObject) client.sendGet("get_suite/" + suiteID);
    }

    /**
     * Create new suite in project, refer https://www.gurock.com/testrail/docs/api/reference/suites
     *
     * @param client:           The APIClient used to make the API requests. Should be initialized prior to calling
     *                          this function.
     * @param projectID:        ID of project in which suite is to be added
     * @param suiteName:        name for new suite
     * @param suiteDescription: description for new suite
     * @return Response of API as JSONObject: Details of new created suite
     * @throws IOException
     * @throws APIException
     */
    public static JSONObject addSuite(APIClient client, long projectID, String suiteName, String suiteDescription) throws IOException, APIException {
        Map post_body = new HashMap();
        post_body.put("name", suiteName);
        post_body.put("description", suiteDescription);
        return (JSONObject) client.sendPost("add_suite/" + projectID, post_body);
    }

    /**
     * Updates the details of suite, refer https://www.gurock.com/testrail/docs/api/reference/suites
     *
     * @param client:      The APIClient used to make the API requests. Should be initialized prior to calling
     *                     this function.
     * @param suiteID:     ID of suite whoese details require updated
     * @param name:        new name for suite to be updated
     * @param description: new description for suite to be updated
     * @return Response of API as JSONObject: Updated details of the suite
     * @throws IOException
     * @throws APIException
     */
    public static JSONObject updateSuite(APIClient client, long suiteID, String name, String description) throws IOException, APIException {
        Map post_body = new HashMap();
        post_body.put("name", name);
        post_body.put("description", description);
        return (JSONObject) client.sendPost("update_suite/" + suiteID, post_body);
    }

    /**
     * Deletes the suite, refer https://www.gurock.com/testrail/docs/api/reference/suites
     *
     * @param client:  The APIClient used to make the API requests. Should be initialized prior to calling
     *                 this function.
     * @param suiteID: ID of suite which needs to be deleted
     * @throws IOException
     * @throws APIException
     */
    public static void deleteSuite(APIClient client, long suiteID) throws IOException, APIException {
        Map post_body = new HashMap();
        client.sendPost("delete_suite/" + suiteID, post_body);
    }

    /**
     * Returns the array of all the sections of the suite, refer https://www.gurock.com/testrail/docs/api/reference/sections
     *
     * @param client:    The APIClient used to make the API requests. Should be initialized prior to calling
     *                   this function.
     * @param projectID: ID of the project
     * @param suiteID:   ID of suite whose sections are required
     * @return Response of API as JSONArray: Details of sections of the suite
     * @throws IOException
     * @throws APIException
     */
    public static JSONArray getSections(APIClient client, long projectID, long suiteID) throws IOException, APIException {
        return (JSONArray) client.sendGet("get_sections/" + projectID + "&suite_id=" + suiteID);
    }

    /**
     * Returns the details of the section, refer https://www.gurock.com/testrail/docs/api/reference/sections
     *
     * @param client:    The APIClient used to make the API requests. Should be initialized prior to calling
     *                   this function.
     * @param sectionID: ID of the section whose details are required
     * @return Response of API as JSONObject: Details of the section
     * @throws IOException
     * @throws APIException
     */
    public static JSONObject getSection(APIClient client, long sectionID) throws IOException, APIException {
        return (JSONObject) client.sendGet("get_section/" + sectionID);
    }

    /**
     * Creates new section in the suite, refer https://www.gurock.com/testrail/docs/api/reference/sections
     *
     * @param client:      The APIClient used to make the API requests. Should be initialized prior to calling
     *                     this function.
     * @param projectID:   ID of the project
     * @param suiteID:     ID of suite in which section is to be added
     * @param sectionName: Name for the section to be created
     * @param parentID:    ID of the parent seciton, null if section is to be added at root
     * @return Response of API as JSONObject: Details of the new section created
     * @throws IOException
     * @throws APIException
     */
    public static JSONObject addSection(APIClient client, long projectID, long suiteID, String sectionName, Long parentID) throws IOException, APIException {
        Map post_body = new HashMap();
        post_body.put("suite_id", suiteID);
        post_body.put("name", sectionName);
        if (parentID != null)
            post_body.put("parent_id", parentID);
        return (JSONObject) client.sendPost("add_section/" + projectID, post_body);
    }

    /**
     * Copies the test cases to section, refer https://www.gurock.com/testrail/docs/api/reference/sections
     *
     * @param client:    The APIClient used to make the API requests. Should be initialized prior to calling
     *                   this function.
     * @param sectionID: ID of the section in which cases are to be copied
     * @param caseIDs:   Comma seperated string of test case IDs which are to be copied
     * @throws IOException
     * @throws APIException
     */
    public static void copyCasesToSection(APIClient client, long sectionID, String caseIDs) throws IOException, APIException {
        Map post_body = new HashMap();
        post_body.put("case_ids", caseIDs);
        client.sendPost("copy_cases_to_section/" + sectionID, post_body);
    }

    /**
     * Returns the test cases present in the section of the suite, refer https://www.gurock.com/testrail/docs/api/reference/cases
     *
     * @param client:    The APIClient used to make the API requests. Should be initialized prior to calling
     *                   this function.
     * @param projectID: ID of the project
     * @param suiteID:   ID of the suite in which section is present
     * @param sectionID: ID od the section whose test cases are required
     * @return Response of API as JSONArray: Details of the test cases
     * @throws IOException
     * @throws APIException
     */
    public static JSONArray getCases(APIClient client, long projectID, long suiteID, long sectionID) throws IOException, APIException {
        return (JSONArray) client.sendGet("get_cases/" + projectID + "&suite_id=" + suiteID + "&section_id=" + sectionID);
    }

    /**
     * Take IDs of test cases from JSONArray and convert them into comma seperated string
     *
     * @param testCases : Array of test cases
     * @return String of comma seperated test case IDs
     */
    private static String getCaseIDs(JSONArray testCases) {
        StringBuilder caseIDs = new StringBuilder();
        for (int i = 0; i < testCases.size(); ++i) {
            JSONObject result = (JSONObject) testCases.get(i);
            caseIDs.append(result.get("id")).append(",");
        }
        return caseIDs.deleteCharAt(caseIDs.lastIndexOf(",")).toString();
    }
}
