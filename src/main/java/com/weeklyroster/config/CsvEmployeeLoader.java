package com.weeklyroster.config;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

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

            while ((line = reader.readLine()) != null) {

                if (header) {
                    header = false;
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

                employee.setEmployeeCode(cols[0].trim());
                employee.setFirstName(cols[1].trim());
                employee.setLastName(cols[2].trim());
                employee.setEmail(cols[3].trim());
                employee.setGender(Gender.valueOf(cols[4].trim().toUpperCase()));
                if (cols.length > 5 && !cols[5].trim().isEmpty()) {
                    employee.setContactNumber(cols[5].trim());
                }

                employees.add(employee);		
            }
        }

        return employees;
    }
}