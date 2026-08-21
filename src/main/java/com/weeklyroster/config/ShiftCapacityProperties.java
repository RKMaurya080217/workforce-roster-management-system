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

	public Map<ShiftType, Integer> asMap() {
		Map<ShiftType, Integer> capacities = new EnumMap<>(ShiftType.class);
		capacities.put(ShiftType.MORNING, morning);
		capacities.put(ShiftType.GENERAL, general);
		capacities.put(ShiftType.EVENING, evening);
		capacities.put(ShiftType.NIGHT, night);
		return capacities;
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
}
