package com.jp.aircall.domain.adapters;

import com.jp.aircall.domain.error.AckAlertingAlreadyException;
import com.jp.aircall.domain.error.ServiceAlertingAlreadyException;
import com.jp.aircall.domain.model.alert.PagerAlert;
import com.jp.aircall.domain.model.policy.Service;
import java.util.Optional;

public interface PersistenceAdapter {

  //semaphore methods
  /**
   * Add the given service to the alerting table.
   * This operation is transactional and will throw a ServiceAlertingAlreadyException if the service already exists
   * This operation should be undone with {@link #removeAlertingService(String)}
   * @param serviceId
   * @throws ServiceAlertingAlreadyException
   */
  void addAlertingService(String serviceId) throws ServiceAlertingAlreadyException;

  /**
   * removes the given service from the alerting table. Does nothing if not exists serviceId in the alerting table
   * @param serviceId
   */
  void removeAlertingService(String serviceId);

  /**
   * Add the given alert to the ack alerting table.
   * This operation is transactional and will throw a AckAlertingAlreadyException if the alert id already exists
   * This operation should be undone with {@link #removeAckSemaphore(String)}
   * @param pagerAlertId
   * @throws ServiceAlertingAlreadyException
   */
  void addAckSempahore(String pagerAlertId) throws AckAlertingAlreadyException;

  /**
   * removes the given alert if from the ack alerting table. Does nothing if not exists the given alert in the alerting table
   * @param pagerAlertId
   */
  void removeAckSemaphore(String pagerAlertId);



  //Service methods

  Optional<Service> getService(String serviceId);

  void updateService(Service service);




  //pager alert methods
  void savePagerAlert(PagerAlert pagerAlert);

  Optional<PagerAlert> getPagerAlert(String pagerAlertId);

  void updatePagerAlert(PagerAlert pagerAlert);

  /**
   * Set status to CLOSED to all alerts where :serviceId is equals than the given serviceId
   * @param serviceId
   */
  void closeAllServiceAlerts(String serviceId);
}
