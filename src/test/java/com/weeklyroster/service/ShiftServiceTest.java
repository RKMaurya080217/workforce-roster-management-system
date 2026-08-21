package com.weeklyroster.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.weeklyroster.dto.response.ShiftResponse;
import com.weeklyroster.entity.Shift;
import com.weeklyroster.entity.ShiftType;
import com.weeklyroster.exception.BusinessException;
import com.weeklyroster.repository.ShiftRepository;

@ExtendWith(MockitoExtension.class)
class ShiftServiceTest {

    @Mock
    private ShiftRepository shiftRepository;

    @Mock
    private com.weeklyroster.repository.EmployeeRepository employeeRepository;

    @InjectMocks
    private ShiftService shiftService;

    @Test
    void testUpdateCapacity_Successful() {
        Shift shift = new Shift();
        shift.setId(1L);
        shift.setShiftType(ShiftType.MORNING);
        shift.setCapacity(2);
        shift.setActive(true);

        when(shiftRepository.findById(1L)).thenReturn(Optional.of(shift));

        ShiftResponse response = shiftService.updateCapacity(1L, 4);

        assertNotNull(response);
        assertEquals(4, response.capacity());
        assertEquals(4, shift.getCapacity());
    }

    @Test
    void testUpdateCapacity_ThrowsException_WhenNegativeCapacity() {
        assertThrows(BusinessException.class, () -> shiftService.updateCapacity(1L, -1));
    }

    @Test
    void testUpdateCapacity_NightShift_Success_WithOne() {
        Shift nightShift = new Shift();
        nightShift.setId(4L);
        nightShift.setShiftType(ShiftType.NIGHT);
        nightShift.setCapacity(1);
        nightShift.setActive(true);

        when(shiftRepository.findById(4L)).thenReturn(Optional.of(nightShift));

        ShiftResponse response = shiftService.updateCapacity(4L, 1);

        assertNotNull(response);
        assertEquals(1, response.capacity());
        assertEquals(1, nightShift.getCapacity());
    }

    @Test
    void testUpdateCapacity_NightShift_ThrowsException_WhenGreaterThanOne_Two() {
        Shift nightShift = new Shift();
        nightShift.setId(4L);
        nightShift.setShiftType(ShiftType.NIGHT);
        nightShift.setCapacity(1);
        nightShift.setActive(true);

        when(shiftRepository.findById(4L)).thenReturn(Optional.of(nightShift));

        BusinessException ex = assertThrows(BusinessException.class, () -> shiftService.updateCapacity(4L, 2));
        assertEquals("Night shift target cannot exceed 1 employee per day.", ex.getMessage());
    }

    @Test
    void testUpdateCapacity_NightShift_ThrowsException_WhenGreaterThanOne_Three() {
        Shift nightShift = new Shift();
        nightShift.setId(4L);
        nightShift.setShiftType(ShiftType.NIGHT);
        nightShift.setCapacity(1);
        nightShift.setActive(true);

        when(shiftRepository.findById(4L)).thenReturn(Optional.of(nightShift));

        BusinessException ex = assertThrows(BusinessException.class, () -> shiftService.updateCapacity(4L, 3));
        assertEquals("Night shift target cannot exceed 1 employee per day.", ex.getMessage());
    }
}
