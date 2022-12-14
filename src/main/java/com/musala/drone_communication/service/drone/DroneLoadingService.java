package com.musala.drone_communication.service.drone;

import com.musala.drone_communication.dao.repository.DroneRepository;
import com.musala.drone_communication.dao.repository.MedicationRepository;
import com.musala.drone_communication.dto.service.DroneDto;
import com.musala.drone_communication.dto.service.LoadingDroneDto;
import com.musala.drone_communication.dto.service.MedicationDto;
import com.musala.drone_communication.enums.DroneState;
import com.musala.drone_communication.exception.DroneIsNotEnoughChargedException;
import com.musala.drone_communication.exception.DroneNotFoundException;
import com.musala.drone_communication.mapper.DroneMapper;
import com.musala.drone_communication.mapper.MedicationMapper;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Collectors;
import static com.musala.drone_communication.service.drone.DroneValidationService.MIN_BATTERY_CAPACITY_FOR_LOADING;

/**
 * This service provides loading operation with drones
 */
@Service
@AllArgsConstructor
@Slf4j
public class DroneLoadingService {

    private static final Map<String, LoadingDroneDto> LOADING_DRONES = new ConcurrentHashMap<>();

    private final DroneValidationService droneValidationService;
    private final MedicationRepository medicationRepository;
    private final DroneRepository droneRepository;
    private final DroneMapper droneMapper;
    private final MedicationMapper medicationMapper;

    /**
     * Method loads given medication into the drone
     *
     * @param droneId     drone's id for loading
     * @param medications medication with its amount
     * @return available weight after loading
     */
    public short loadDrone(String droneId, Map<String, Integer> medications) {
        LoadingDroneDto loadingDroneDto;
        synchronized (LOADING_DRONES) {
            if (!LOADING_DRONES.containsKey(droneId)) {
                DroneDto droneDto = droneRepository.findById(droneId)
                        .map(droneMapper::mapToServiceDto)
                        .orElseThrow(() -> new DroneNotFoundException("There is no drone with id=" + droneId));
                loadingDroneDto = new LoadingDroneDto(droneDto, new HashMap<>());
                LOADING_DRONES.put(droneId, loadingDroneDto);
            } else {
                loadingDroneDto = LOADING_DRONES.get(droneId);
            }
        }
        synchronized (loadingDroneDto) {
            final var medicationsToLoad =
                    medicationRepository.findAllByCodeIn(medications.keySet())
                            .stream()
                            .map(medicationMapper::toServiceDto)
                            .collect(Collectors.toMap(
                                    Function.identity(),
                                    medicationDto -> medications.get(medicationDto.getCode())));
            try {
                droneValidationService.checkBeforeLoading(loadingDroneDto, medicationsToLoad);
            } catch (DroneIsNotEnoughChargedException e) {
                stopDroneLoading(e.getDroneDto());
                throw e;
            }

            updateDroneLoadedMedication(medicationsToLoad, loadingDroneDto);
            return (short) (loadingDroneDto.getDroneDto().getWeightLimit() -
                    loadingDroneDto.getCurrentLoadedWeight().shortValue());
        }
    }

    /**
     * Method searches for all available for loading drones
     *
     * @return list of available for loading drones
     */
    public List<DroneDto> getAvailableDrones() {
        return droneMapper.mapToServiceDtoList(
                droneRepository.findAllByStateIsAndBatteryCapacityGreaterThanEqual(
                        DroneState.IDLE, MIN_BATTERY_CAPACITY_FOR_LOADING));
    }

    /**
     * Method checks loaded into the drone medications
     *
     * @param droneId drone's id
     * @return loaded into given drone medication
     */
    public Map<MedicationDto, Integer> getLoadedInDroneMedication(String droneId) {
        return Optional.ofNullable(LOADING_DRONES.get(droneId))
                .map(LoadingDroneDto::getLoadedMedication)
                .orElse(Collections.emptyMap());
    }

    public void updateLoadingDrone(DroneDto updatedDrone) {
        synchronized (LOADING_DRONES) {
            if (LOADING_DRONES.containsKey(updatedDrone.getSerialNumber())) {
                final var loadingDroneDto = LOADING_DRONES.get(updatedDrone.getSerialNumber());
                synchronized (loadingDroneDto) {
                    loadingDroneDto.setDroneDto(updatedDrone);
                }
            }
        }
    }

    /**
     * Method stops drone's loading
     */
    public void stopDroneLoading(DroneDto droneDto) {
        LOADING_DRONES.remove(droneDto.getSerialNumber());
        droneRepository.setDroneState(droneDto.getSerialNumber(), DroneState.IDLE);
    }

    private void updateDroneLoadedMedication(Map<MedicationDto, Integer> medicationsToLoad,
                                             LoadingDroneDto loadingDroneDto) {
        final var loadedMedication = loadingDroneDto.getLoadedMedication();
        medicationsToLoad
                .forEach((key, value) -> {
                    if (loadedMedication.containsKey(key)) {
                        loadedMedication.put(key, value + loadedMedication.get(key));
                    } else {
                        loadedMedication.put(key, value);
                    }
                });
        if(loadingDroneDto.getDroneDto().getState() != DroneState.LOADING) {
            loadingDroneDto.getDroneDto().setState(DroneState.LOADING);
            droneRepository.setDroneState(loadingDroneDto.getDroneDto().getSerialNumber(), DroneState.LOADING);
        }
    }
}
