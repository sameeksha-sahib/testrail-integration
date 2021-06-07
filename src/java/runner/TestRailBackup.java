package runner;

import com.univocity.parsers.common.record.Record;
import com.univocity.parsers.csv.CsvFormat;
import com.univocity.parsers.csv.CsvParser;
import com.univocity.parsers.csv.CsvParserSettings;
import org.apache.commons.io.FilenameUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.json.simple.JSONObject;
import testrail.APIClient;
import utils.CreateCsvUtils;

import java.io.FileReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static testrail.TestRailRule.*;

public class TestRailBackup {
    private static final Logger logger = LogManager.getLogger(TestRailBackup.class);

    public static void main(String[] args) throws IOException {
        logger.info("<-------------------- Started -------------------->");
        APIClient client = setApiClient();

        List<String[]> rows = new ArrayList<>();
        List<Record> allRecords = getBackupRecordsFromCSV();
        try {
            for (Record record : allRecords) {
                logger.info("Record: " + record);
                // Update name of Backup 2 to Backup 1
                long suiteID = Long.parseLong(record.getString("Backup 2"));
                JSONObject suiteDetails = getSuite(client, suiteID);
                String newSuiteName = suiteDetails.get("name").toString().replace("Backup 2", "Backup 1");
                updateSuite(client, suiteID, newSuiteName, (String) suiteDetails.get("description"));

                // Delete Backup 1
                deleteSuite(client, Long.parseLong(record.getString("Backup 1")));

                // Create new Backup 2
                long backupSuiteID = createBackupOfSuite(client, (Long.parseLong(record.getString("Project ID"))), (Long.parseLong(record.getString("Suite ID"))), "Backup 2");
                logger.info("Backup for Suite: " + record.getString("Suite Name") + " is created with ID: " + backupSuiteID);

                // Store backup IDs for next run
                rows.add(new String[]{record.getString("Suite Name"), record.getString("Suite ID"), record.getString("Project ID"), String.valueOf(backupSuiteID), String.valueOf(suiteID)});
                logger.info("<----------------------------------------------------------->");
            }
        } catch (Exception e) {
            logger.info("ALL RECORDS NOT BACKED UP, Backed Up count: " + rows.size());
            logger.error("Error: " + e.getMessage());
            e.printStackTrace();
        } finally {
            CreateCsvUtils.createCsv("Backup Suite IDs.csv", Arrays.asList("Suite Name", "Suite ID", "Project ID", "Backup 2", "Backup 1"), rows);
            logger.info("Records of Backup created saved");
        }
    }

    // Read CSV for previous backup suite IDs
    private static List<Record> getBackupRecordsFromCSV() throws IOException {
        logger.info("Getting old backup records");
        List<Record> allRecords = null;
        String pathname = FilenameUtils.normalize(System.getProperty("user.dir") + "/src/main/resources/testdata/");
        try (Stream<Path> walk = Files.walk(Paths.get(pathname))) {
            List<String> result = walk.map(x -> x.toString()).filter(f -> f.contains("Backup Suite IDs.csv"))
                    .collect(Collectors.toList());
            logger.info("Backup File Name: Backup Suite IDs.csv");
            CsvParserSettings csvParserSettings = new CsvParserSettings();
            CsvFormat csvFormat = new CsvFormat();
            csvFormat.setDelimiter(',');
            csvParserSettings.setFormat(csvFormat);
            csvParserSettings.getFormat().setLineSeparator("\n");
            csvParserSettings.setHeaderExtractionEnabled(true);
            csvParserSettings.selectFields("Suite Name", "Suite ID", "Project ID", "Backup 2", "Backup 1");
            CsvParser csvParser = new CsvParser(csvParserSettings);

            allRecords = csvParser
                    .parseAllRecords(new FileReader(result.stream().max(Comparator.comparing(String::valueOf)).get()));
        } catch (IOException e) {
            e.printStackTrace();
        }
        return allRecords;
    }
}
