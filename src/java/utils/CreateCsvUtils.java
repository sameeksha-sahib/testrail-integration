package utils;

import com.univocity.parsers.csv.CsvFormat;
import com.univocity.parsers.csv.CsvWriter;
import com.univocity.parsers.csv.CsvWriterSettings;
import org.apache.commons.io.FilenameUtils;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.List;


public class CreateCsvUtils {

    public static String createCsv(String fileName, List<String> headers, List<String[]> rows) {
        CsvWriterSettings settings = new CsvWriterSettings();
        CsvFormat csvFormat = new CsvFormat();
        csvFormat.setDelimiter(",");
        csvFormat.setLineSeparator("\n");
        settings.setFormat(csvFormat);
        settings.setHeaderWritingEnabled(true);
        BufferedWriter writer = null;
        String pathname = FilenameUtils.normalize(System.getProperty("user.dir") + "/src/main/resources/testdata/" + fileName);
        try {
            writer = new BufferedWriter(new FileWriter(new File(pathname)));
        } catch (IOException e) {
            e.printStackTrace();
        }
        CsvWriter csvWriter = new CsvWriter(writer, settings);

        csvWriter.writeHeaders(headers);

        for (String[] row : rows) {
            csvWriter.writeRow(row);
        }
        csvWriter.close();
        return pathname;
    }
}
