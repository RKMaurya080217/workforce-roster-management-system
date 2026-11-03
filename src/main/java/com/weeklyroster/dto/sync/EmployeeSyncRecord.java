package com.weeklyroster.dto.sync;

import com.weeklyroster.entity.Gender;
import java.time.LocalDate;

public record EmployeeSyncRecord(
        String employeeCode,
        String firstName,
        String lastName,
        String email,
        Gender gender,
        Boolean active,
        String contactNumber,
        LocalDate joiningDate,
        LocalDate resignationDate,
        String department,
        Boolean developmentTeam,
        Integer maxNightsPerWeek,
        String pairedEmployeeCode
) {
    public static final String CANONICAL_HEADER = 
            "employeeCode,firstName,lastName,email,gender,active,contactNumber,joiningDate,resignationDate,department,developmentTeam,maxNightsPerWeek,pairedEmployeeCode";

    public String toCsvLine() {
        return String.join(",",
                safe(employeeCode),
                safe(firstName),
                safe(lastName),
                safe(email),
                gender != null ? gender.name() : "MALE",
                active != null ? String.valueOf(active) : "true",
                safe(contactNumber),
                joiningDate != null ? joiningDate.toString() : "",
                resignationDate != null ? resignationDate.toString() : "",
                safe(department != null ? department : "OPERATIONS"),
                developmentTeam != null ? String.valueOf(developmentTeam) : "false",
                maxNightsPerWeek != null ? String.valueOf(maxNightsPerWeek) : "",
                safe(pairedEmployeeCode)
        );
    }

    private static String safe(String val) {
        if (val == null) return "";
        return val.replace(",", " ").trim();
    }
}
