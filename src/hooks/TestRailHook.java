package hooks;

import testrail.APIClient;
import testrail.APIException;
import utils.PropertyUtils;
import cucumber.api.Result;
import cucumber.api.Scenario;
import cucumber.api.java.After;
import cucumber.runtime.ScenarioImpl;
import org.apache.commons.lang3.reflect.FieldUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;

import java.io.IOException;
import java.lang.reflect.Field;
import java.util.*;

import static testrail.TestRailRule.getRunIDs;
import static testrail.TestRailRule.setApiClient;

public class TestRailHook {

    private static final Logger logger = LogManager.getLogger(TestRailHook.class);
    public static HashSet<String> failedCases = new HashSet<>();

    // CE test plan details
    public static String PROJECT_ID = PropertyUtils.getProperty("testrail.projectid");
    public static String NEW_PLAN_NAME = PropertyUtils.getProperty("testrail.plan.name");
    public static String BASE_PLAN_ID = PropertyUtils.getProperty("testrail.planid");

    // IDs of runs of in the test plans
    private static List<Long> TEST_RUN_IDS = new ArrayList<>();
    private static final String TEST_CASE_PASSED_STATUS = "1";
    private static final String TEST_CASE_FAILED_STATUS = "5";

    /**
     * Before all: Create plan, if not already created and set test run ids
     * After all: mark status of cases as 'Failed' which were fail even once during execution.
     */

    static {
        try {
            testrailHook();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static void testrailHook() throws Exception {
        // After hook: Marks the cases as failed, if they were failed atleast once during execution
        Runtime.getRuntime().addShutdownHook(new Thread() {
            public void run() {
                try {
                    String[] failedCasesArr = failedCases.toArray(new String[0]);
                    addResultForCases(failedCasesArr, TEST_CASE_FAILED_STATUS, " Test Case Failed Atleast Once !!!");
                    logger.info("Cases Marked !!!");
                } catch (Exception exception) {
                    exception.printStackTrace();
                    logger.debug(exception);
                }
            }
        });
        // Before hook: Set IDs of child runs of the plan
        List<Long> runIDs = getRunIDs(PROJECT_ID, NEW_PLAN_NAME, BASE_PLAN_ID);
        TEST_RUN_IDS.addAll(runIDs);
        logger.info("TEST_RUN_IDS --->>>" + TEST_RUN_IDS);
    }

    /**
     * Markes status of cases on the basis of scenario status in the runs present in the test plan
     * Test case ids are extracted from the tag @TestRailId-### where ### is the test case id in the testrail
     *
     * @param scenario: Current executed scenario
     */
    @After(order = 0)
    public void afterScenario(Scenario scenario) {
        // Get passed or failed status of scenatio
        String status_id = scenario.getStatus().equals(Result.Type.PASSED) ? TEST_CASE_PASSED_STATUS : TEST_CASE_FAILED_STATUS;

        // Set comment for test case
        StringBuilder status_message = new StringBuilder();
        if (status_id.equals(TEST_CASE_PASSED_STATUS)) {
            status_message.append(scenario.getName()).append(": Test has passed ");
        } else {
            // if scenario failed, error message and stacktrace is set as comment in the testrail case
            status_message.append(scenario.getName()).append(": Test has failed! -- ");
            Throwable error = logError(scenario);
            if (error != null) {
                status_message.append(error.getMessage()).append("\n ");
                for (StackTraceElement stackTraceElement : error.getStackTrace()) {
                    status_message.append(stackTraceElement.toString()).append("\n");
                }
            }
        }

        // exctract test case ids from scenario
        String[] testRailIds = extractTestRailIds(scenario);

        // if test case ids are present on scenario, find them in the runs and mark their status
        if (testRailIds != null) {
            addResultForCases(testRailIds, status_id, status_message.toString());
        }
    }

    /**
     * Mark result status of the test case ids related to scenario
     *
     * @param testRailIds:    Test case IDs related to scenario
     * @param status_id:      Status of scenario: Passed or failed
     * @param status_message: Comment for test case status
     */
    private static void addResultForCases(String[] testRailIds, String status_id, String status_message) {
        for (String testRailId : testRailIds) {
            // Initialize APIClient with testrail credentials
            APIClient client = setApiClient();

            Map data = new HashMap();
            data.put("status_id", status_id);
            data.put("comment", status_message);
            for (Long testRunId : TEST_RUN_IDS) {
                try {
                    // If cases id failed even once before, add it to failed cases set
                    if (getResultsOfCase(client, testRunId, testRailId).contains(5L))
                        failedCases.add(testRailId);

                    // add result for the test case
                    String uri = "add_result_for_case/" + testRunId + "/" + testRailId + "";
                    client.sendPost(uri, data);
                    break;
                } catch (APIException | IOException exception) {
                    exception.printStackTrace();
                    logger.info(testRunId + "/" + testRailId + ": " + exception);
                }
            }
        }
    }

    /**
     * Extract test case ids from the scenario tag @TestRailId-### where ### is the test case id in the testrail
     *
     * @param scenario: Current executed scenario
     * @return Testrail IDs of the test cases related to scenario
     */
    private String[] extractTestRailIds(Scenario scenario) {
        for (String tag : scenario.getSourceTagNames()) {
            if (tag.contains("TestRailId-")) {
                return tag.split("-")[1].split(",");
            }
        }
        return null;
    }

    /**
     * Logs and returns the failure error of scenario
     *
     * @param scenario: Current executed scenario
     * @return Failure error
     */
    private static Throwable logError(Scenario scenario) {
        Field field = FieldUtils.getField(((ScenarioImpl) scenario).getClass(), "stepResults", true);
        field.setAccessible(true);
        try {
            Throwable error = ((ScenarioImpl) scenario).getError();
            logger.error("Error Scenario: {}", scenario.getId(), error);
            return error;
        } catch (Exception exception) {
            logger.error("Error while logging error", exception);
        }
        return null;
    }

    /**
     * Get previous results of the test case
     *
     * @param client:     The APIClient used to make the API requests. Should be initialized prior to calling
     *                    this function.
     * @param runID:      Testrail ID of the run in which test case is present
     * @param testCaseID: Testrail ID of the test case of which results are required
     * @return List of all the statuses of the test case (Passed, failed or untested)
     * @throws IOException
     * @throws APIException
     */
    private static List<Long> getResultsOfCase(APIClient client, Long runID, String testCaseID) throws IOException, APIException {
        String uri = "get_results_for_case/" + runID + "/" + testCaseID;
        JSONArray response = (JSONArray) client.sendGet(uri);
        List<Long> results = new ArrayList<>();
        for (int i = 0; i < response.size(); ++i) {
            JSONObject result = (JSONObject) response.get(i);
            results.add((Long) result.get("status_id"));
        }
        return results;
    }
}
