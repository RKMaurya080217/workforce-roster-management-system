package com.weeklyroster.config;

import java.util.EnumMap;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;

import com.weeklyroster.entity.ShiftType;

@ConfigurationProperties(prefix = "app.shift-capacity")
public class ShiftCapacityProperties {
	private int morning = 2;
	private int general = 2;
	private int evening = 2;
	private int night = 1;

	private int weekdayMorningMin = 2;
	private int weekendMorningMin = 1;
	private int eveningMin = 1;
	private int nightMin = 1;
	private int generalMin = 1;

	public Map<ShiftType, Integer> asMap() {
		Map<ShiftType, Integer> capacities = new EnumMap<>(ShiftType.class);
		capacities.put(ShiftType.MORNING, morning);
		capacities.put(ShiftType.GENERAL, general);
		capacities.put(ShiftType.EVENING, evening);
		capacities.put(ShiftType.NIGHT, night);
		return capacities;
	}

	public int getMinimumStaff(ShiftType type, java.time.DayOfWeek dayOfWeek) {
		if (type == ShiftType.MORNING) {
			return (dayOfWeek == java.time.DayOfWeek.SATURDAY || dayOfWeek == java.time.DayOfWeek.SUNDAY)
					? weekendMorningMin : weekdayMorningMin;
		}
		if (type == ShiftType.EVENING) return eveningMin;
		if (type == ShiftType.NIGHT) return nightMin;
		if (type == ShiftType.GENERAL) return generalMin;
		return 0;
	}

	public int getMinimumStaff(ShiftType type, java.time.LocalDate date) {
		return date != null ? getMinimumStaff(type, date.getDayOfWeek()) : getMinimumStaff(type, java.time.DayOfWeek.MONDAY);
	}

	public int getMorning() {
		return morning;
	}

	public void setMorning(int morning) {
		this.morning = morning;
	}

	public int getGeneral() {
		return general;
	}

	public void setGeneral(int general) {
		this.general = general;
	}

	public int getEvening() {
		return evening;
	}

	public void setEvening(int evening) {
		this.evening = evening;
	}

	public int getNight() {
		return night;
	}

	public void setNight(int night) {
		this.night = night;
	}

	public int getWeekdayMorningMin() {
		return weekdayMorningMin;
	}

	public void setWeekdayMorningMin(int weekdayMorningMin) {
		this.weekdayMorningMin = weekdayMorningMin;
	}

	public int getWeekendMorningMin() {
		return weekendMorningMin;
	}

	public void setWeekendMorningMin(int weekendMorningMin) {
		this.weekendMorningMin = weekendMorningMin;
	}

	public int getEveningMin() {
		return eveningMin;
	}

	public void setEveningMin(int eveningMin) {
		this.eveningMin = eveningMin;
	}

	public int getNightMin() {
		return nightMin;
	}

	public void setNightMin(int nightMin) {
		this.nightMin = nightMin;
	}

	public int getGeneralMin() {
		return generalMin;
	}

	public void setGeneralMin(int generalMin) {
		this.generalMin = generalMin;
	}
}
