package utils;

import org.apache.commons.io.FilenameUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.*;
import java.util.ArrayList;
import java.util.List;

public class FileUtils {

    private static final Logger logger = LogManager.getLogger(FileUtils.class);

    public static List<File> getFiles(File dir) {

        List<File> propertyFiles = new ArrayList<File>();
        File[] fileList = dir.listFiles();
        for (File file : fileList) {
            if (!file.isDirectory())
                propertyFiles.add(file);
            else
                logger.warn(String.format(
                        "found directory [%s] inside properties of properties directory.\nif desirable, implement new method to load recursively",
                        file.getName()));
        }
        return propertyFiles;
    }

    public static String getFileContent(InputStream inputStream) {

        try (BufferedReader br = new BufferedReader(new InputStreamReader(inputStream))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) {
                sb.append(line);
            }
            return sb.toString();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public static String getFileContent(String fileName) {

        ClassLoader classLoader = FileUtils.class.getClassLoader();
        File file = new File(
                FilenameUtils.normalize(classLoader.getResource("/testdata/" + fileName).getFile()));
        InputStream inputStream = null;
        try {
            inputStream = new FileInputStream(file);
            return getFileContent(inputStream);
        } catch (Exception e) {
            throw new RuntimeException(e);
        } finally {
            try {
                if (inputStream != null) {
                    inputStream.close();
                }
            } catch (Exception ex) {

            }

        }
    }

}
