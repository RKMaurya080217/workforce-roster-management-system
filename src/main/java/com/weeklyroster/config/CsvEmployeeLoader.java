package com.weeklyroster.config;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import com.weeklyroster.entity.Gender;

@Component
public class CsvEmployeeLoader {

    private static final String FILE_NAME = "employees.csv";

	public List<SeedEmployee> loadEmployees() throws IOException {

        List<SeedEmployee> employees = new ArrayList<>();

        ClassPathResource resource = new ClassPathResource(FILE_NAME);

        try (InputStream inputStream = resource.getInputStream();
             BufferedReader reader = new BufferedReader(
                     new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {

            String line;
            boolean header = true;
            Map<String, Integer> colIndex = new HashMap<>();

            while ((line = reader.readLine()) != null) {

                if (header) {
                    header = false;
                    String[] hCols = line.split(",", -1);
                    for (int i = 0; i < hCols.length; i++) {
                        colIndex.put(hCols[i].trim().toLowerCase(), i);
                    }
                    continue;
                }

                if (line.trim().isEmpty()) {
                    continue;
                }

                String[] cols = line.split(",", -1);

                if (cols.length < 5) {
                    throw new RuntimeException("Invalid CSV row : " + line);
                }

                SeedEmployee employee = new SeedEmployee();

                int codeIdx = colIndex.getOrDefault("employeecode", 0);
                int firstIdx = colIndex.getOrDefault("firstname", 1);
                int lastIdx = colIndex.getOrDefault("lastname", 2);
                int emailIdx = colIndex.getOrDefault("email", 3);
                int genderIdx = colIndex.getOrDefault("gender", 4);
                int contactIdx = colIndex.getOrDefault("contactnumber", colIndex.containsKey("active") ? 6 : 5);

                employee.setEmployeeCode(cols[codeIdx].trim());
                employee.setFirstName(cols[firstIdx].trim());
                employee.setLastName(cols[lastIdx].trim());
                employee.setEmail(cols[emailIdx].trim());
                employee.setGender(Gender.valueOf(cols[genderIdx].trim().toUpperCase()));

                if (contactIdx < cols.length && !cols[contactIdx].trim().isEmpty()) {
                    employee.setContactNumber(cols[contactIdx].trim());
                }

                employees.add(employee);		
            }
        }

        return employees;
    }
}
