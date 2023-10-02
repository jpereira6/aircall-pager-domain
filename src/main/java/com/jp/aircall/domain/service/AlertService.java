package com.jp.aircall.domain.service;


import com.jp.aircall.domain.adapters.EPAdapter;
import com.jp.aircall.domain.adapters.MailAdapter;
import com.jp.aircall.domain.adapters.PersistenceAdapter;
import com.jp.aircall.domain.adapters.SmsAdapter;
import com.jp.aircall.domain.adapters.TimerAdapter;
import com.jp.aircall.domain.error.AckAlertingAlreadyException;
import com.jp.aircall.domain.error.ServiceAlertingAlreadyException;
import com.jp.aircall.domain.model.alert.AlertEvent;
import com.jp.aircall.domain.model.alert.PagerAlert;
import com.jp.aircall.domain.model.alert.PagerAlert.ALERT_STATUS;
import com.jp.aircall.domain.model.policy.EscalationPolicy;
import com.jp.aircall.domain.model.policy.Service;
import com.jp.aircall.domain.model.policy.Service.SERVICE_STATUS;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@RequiredArgsConstructor
@Slf4j
public class AlertService {
  private static final long ACK_TIMEOUT_MS = 15 * 60 * 1000; //15 min

  private final EPAdapter epAdapter;
  private final PersistenceAdapter persistenceAdapter;
  private final MailAdapter mailAdapter;
  private final SmsAdapter smsAdapter;
  private final TimerAdapter timerAdapter;


  /**
   * Process a new alert event from the Alerting Service.
   * @param alertEvent the received alert
   */
  public void newAlert(AlertEvent alertEvent){
    log.info("Received new alert event: [{}]", alertEvent);

    Optional<Service> serviceOpt = persistenceAdapter.getService(alertEvent.getServiceId());
    if (serviceOpt.isPresent()) {
      Service service = serviceOpt.get();
      switch (service.getStatus()) {
        case HEALTHY:
          processAlert(alertEvent, service);
          break;

        case UNHEALTHY:
          log.info("Service [{}] already unhealthy. Ignoring alert [{}]", alertEvent.getServiceId(), alertEvent);
          break;
      }
    }else {
      log.warn("Service id not found:[{}]", alertEvent.getServiceId());
    }
  }

  /**
   * receives an alert acknowledgement. If the alert status was open set it to Acknowledgement and updates it on the DB.
   * If not, ignore acknowledgement.
   * Note: This method does not care about concurrent because it is not a problem. If we receive several acknowledgement
   * at the same time of same id, the alert status will be set to acknowledgement without any issues. I mean, it is not a
   * problem to set twice the status to acknowledgement to the same id
   * @param pagerAlertId
   */
  public void alertAcknowledgement(String pagerAlertId){
    log.info("Received alert acknowledgement: [{}]", pagerAlertId);
    Optional<PagerAlert> pagerAlertOpt = persistenceAdapter.getPagerAlert(pagerAlertId);
    if (pagerAlertOpt.isPresent()) {
      PagerAlert pagerAlert = pagerAlertOpt.get();
      switch (pagerAlert.getStatus()) {
        case OPEN:
          pagerAlert.setAcknowledgmentTs(System.currentTimeMillis());
          pagerAlert.setStatus(ALERT_STATUS.ACKNOWLEDGED);
          persistenceAdapter.updatePagerAlert(pagerAlert);
          break;

        case ACKNOWLEDGED:
          log.debug("Pager alert already acknowledged [{}]", pagerAlert);
          break;

        case CLOSED:
          log.debug("Pager alert already closed [{}]", pagerAlert);
          break;
      }
    }else {
      log.warn("Pager alert id not found:"+pagerAlertId);
    }
  }


  /**
   * Received a service healthy event. If the service was already healthy ignore it.
   * Note: This method does not care about concurrency because is not a problem to set a service healthy twice
   * @param serviceId
   */
  public void serviceHealthy(String serviceId){
    log.info("Received service healthy for service id:[{}]", serviceId);
    Optional<Service> serviceOpt = persistenceAdapter.getService(serviceId);
    if (serviceOpt.isPresent()) {
      Service service = serviceOpt.get();
      switch (service.getStatus()) {
        case HEALTHY:
          log.debug("Service already healthy: [{}]", service);
          break;

        case UNHEALTHY:
          /*in order to avoid de-synchronizations between alerts and service status would be better if this fragment of code
          be transactional at DB level. But anyway, if some error occurs between closing alerts and setting service status to healthy won't be
          a real problem for this pager because #acknowledgementTimeout check alert status in order to notify next levels*/
          persistenceAdapter.closeAllServiceAlerts(serviceId);

          service.setStatus(SERVICE_STATUS.HEALTHY);
          persistenceAdapter.updateService(service);
          break;
      }
    }else {
      log.warn("Service id not found:[{}]", serviceId);
    }
  }


  /**
   * process an acknowledgement timeout from the timer for the given alert id.
   * @param pagerAlertId
   */
  public void acknowledgementTimeout(String pagerAlertId) {
    log.info("Received acknowledgement timeout for alert: [{}]", pagerAlertId);
    Optional<PagerAlert> pagerAlertOpt = persistenceAdapter.getPagerAlert(pagerAlertId);
    if (pagerAlertOpt.isPresent()){
      PagerAlert pagerAlert = pagerAlertOpt.get();
      switch (pagerAlert.getStatus()){
        case OPEN:
          //it is not required to check service health status because all service alerts are closed when service healthy event
          //is received. So, if some alert is open means that service is unhealthy
          processAckTimeout(pagerAlertId, pagerAlert);
          break;

        case ACKNOWLEDGED:
          log.debug("Alert already acknowledged: [{}]", pagerAlert);
          break;

        case CLOSED:
          //if it won't be possible to make transactional setting healthy a service on #serviceHealthy here we could check the service
          //status ensuring that is healthy in order to fix possibles de-synchronizations between alerts and service status
          log.debug("Alert already closed: [{}]", pagerAlert);
          break;
      }

    }else {
      log.error("Not found pager alert id:[{}]", pagerAlertId);
    }
  }

  private void processAckTimeout(String pagerAlertId, PagerAlert pagerAlert) {
    if (pagerAlert.getLastNotificationTs() > (System.currentTimeMillis() - ACK_TIMEOUT_MS)){
      log.warn("This alert [{}] was already notified in the last 15 min. Ignoring ack", pagerAlert);
      return;
    }

    if (checkAndCloseAckSemaphore(pagerAlertId)) {
      try {
        int nextLevel = pagerAlert.getLevelNotified() + 1;

        if (notifyLevel(pagerAlert.getServiceId(), nextLevel)) {
          pagerAlert.setLastNotificationTs(System.currentTimeMillis());
          pagerAlert.setLevelNotified(nextLevel);
          persistenceAdapter.updatePagerAlert(pagerAlert);
        }

        /* Note: as it is not clear in the specification how to do with timer in this case I decide to set it up anyway
        because it enables to notify further levels added to the policy meanwhile the alert is not closed    */
        setTimer(pagerAlertId);
      }finally {
        openAckSemaphore(pagerAlertId);
      }
    }
  }


  private void processAlert(AlertEvent alertEvent, Service service) {
    if (checkAndCloseAlertingServiceSemaphore(alertEvent.getServiceId())) {
      try {
        //mark services as unhealthy
        service.setStatus(SERVICE_STATUS.UNHEALTHY);
        persistenceAdapter.updateService(service);

        boolean notified = notifyLevel(service.getId(), 1);
        String id = savePagerAlert(alertEvent, notified);
        setTimer(id);
      }finally {
        openAlertingSemaphore(alertEvent); //ensure the semaphore is opened at the end
      }
    }
  }


  /**
   * notify the given level for the given service. Returns true if some target has been notified. False if there are no policies
   * or all target has been already notified
   * @param serviceId
   * @param level
   * @return
   */
  private boolean notifyLevel(String serviceId, int level) {
    Optional<EscalationPolicy> epOpt = epAdapter.getEpByServiceId(serviceId);
    if (epOpt.isPresent()) {
      EscalationPolicy ep = epOpt.get();
      if (ep.getLevels().size() >= level) {
        ep.getLevels().get(level - 1).getTargets().forEach(target -> {  //first level is 1
          switch (target.getType()) {
            case SMS:
              smsAdapter.sendNotification(target.getValue());
              break;
            case EMAIL:
              mailAdapter.sendNotification(target.getValue());
              break;
          }
        });
        return true;
      } else {
        log.debug("All levels has been notified for service [{}]- EscalationPolicy:][{}]", serviceId, ep);
        return false;
      }
    }else {
      log.error("Not found any escalation policy for serviceId:[{}]", serviceId);
      return false;
    }
  }


  private String savePagerAlert(AlertEvent alertEvent, boolean notified) {
    long now = System.currentTimeMillis();
    long lastNotifTs = notified ? now : 0;
    int level = notified ? 1: 0;
    PagerAlert pagerAlert =
        new PagerAlert(alertEvent.getServiceId(), alertEvent.getMessage(), ALERT_STATUS.OPEN, level, now, lastNotifTs);
    persistenceAdapter.savePagerAlert(pagerAlert);
    return pagerAlert.getId();
  }

  private void setTimer(String id) {
    timerAdapter.add15MinutesTimer(id);
  }


  /** alerting service table acts as a semaphore. only permits to process (and notify) one alert at a time for the same service */
  private boolean checkAndCloseAlertingServiceSemaphore(String serviceId) {
    try {
      persistenceAdapter.addAlertingService(serviceId);
      return true;

    } catch (ServiceAlertingAlreadyException e) {
      log.info("Some other alert of the given service ["+serviceId+"] is already being processed so this alert will be discarded");
      return false;
    }
  }

  private void openAlertingSemaphore(AlertEvent alertEvent) {
    persistenceAdapter.removeAlertingService(alertEvent.getServiceId());
  }



  /**
   * if due to some error on the timer or anywhere we ensure that only one is processed to avoid notifying twice the targets
   * @param pagerAlertId
   * @return
   */
  private boolean checkAndCloseAckSemaphore(String pagerAlertId) {
    try {
      persistenceAdapter.addAckSempahore(pagerAlertId);
      return true;

    } catch (AckAlertingAlreadyException e) {
      log.info("Some other ack of the givren alert id ["+pagerAlertId+"] is already being processed so this ack will be discarded");
      return false;
    }
  }

  private void openAckSemaphore(String pagerAlertId) {
    persistenceAdapter.removeAckSemaphore(pagerAlertId);
  }


}
