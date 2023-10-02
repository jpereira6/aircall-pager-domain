package com.jp.aircall.domain.service;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.MockitoAnnotations.openMocks;

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
import com.jp.aircall.domain.model.policy.EmailTarget;
import com.jp.aircall.domain.model.policy.EscalationPolicy;
import com.jp.aircall.domain.model.policy.Level;
import com.jp.aircall.domain.model.policy.Service;
import com.jp.aircall.domain.model.policy.Service.SERVICE_STATUS;
import com.jp.aircall.domain.model.policy.SmsTarget;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;

public class AlertServiceTest {

  @Mock private EPAdapter epAdapter;
  @Mock private MailAdapter mailAdapter;
  @Mock private PersistenceAdapter persistenceAdapter;
  @Mock private SmsAdapter smsAdapter;
  @Mock private TimerAdapter timerAdapter;

  private AlertService alertService;

  @Before
  public void setUp() {
    openMocks(this);
    alertService = new AlertService(epAdapter, persistenceAdapter, mailAdapter, smsAdapter,timerAdapter);
  }


  @Test
  public void testScenario1() throws ServiceAlertingAlreadyException, AckAlertingAlreadyException {
    /*
    Given a Monitored Service in a Healthy State,
    when the Pager receives an Alert related to this Monitored Service,
    then the Monitored Service becomes Unhealthy,
    the Pager notifies all targets of the first level of the escalation policy,
    and sets a 15-minutes acknowledgement delay
     */
    String serviceId = "Service1";

    Service service = new Service(serviceId, SERVICE_STATUS.HEALTHY);
    when(persistenceAdapter.getService(serviceId)).thenReturn(Optional.of(service));

    EscalationPolicy policy1 = new EscalationPolicy("service1",
        List.of(
            new Level(Set.of(new EmailTarget("user1@mail.com"), new SmsTarget("+34666666666"), new SmsTarget("678912345"))),
            new Level(Set.of(new EmailTarget("user2@mail.com")))));
    when(epAdapter.getEpByServiceId(serviceId)).thenReturn(Optional.of(policy1));

    alertService.newAlert(new AlertEvent("Alert1 Message", serviceId));

    verify(mailAdapter, times(1)).sendNotification("user1@mail.com");
    verify(smsAdapter, times(2)).sendNotification(ArgumentMatchers.anyString());
    verify(persistenceAdapter, times(1)).updateService( ArgumentMatchers.eq(new Service(serviceId, SERVICE_STATUS.UNHEALTHY)));
    verify(timerAdapter, times(1)).add15MinutesTimer(ArgumentMatchers.anyString());
    verify(persistenceAdapter, times(1)).savePagerAlert(argThat(pagerAlert1 ->
        pagerAlert1.getLevelNotified() == 1 &&
        pagerAlert1.getLastNotificationTs() > 0 &&
        pagerAlert1.getCreationTs() > 0 &&
        pagerAlert1.getStatus() == ALERT_STATUS.OPEN));
    assertEquals(SERVICE_STATUS.UNHEALTHY, service.getStatus());
    verify(persistenceAdapter, times(1)).addAlertingService(serviceId);
    verify(persistenceAdapter, times(1)).removeAlertingService(serviceId);
    verify(persistenceAdapter, never()).addAckSempahore(any());
    verify(persistenceAdapter, never()).removeAckSemaphore(any());
  }

  @Test
  public void testScenario2() throws ServiceAlertingAlreadyException, AckAlertingAlreadyException {
   /*
   Given a Monitored Service in an Unhealthy State,
   the corresponding Alert is not Acknowledged
   and the last level has not been notified,
   when the Pager receives the Acknowledgement Timeout,
   then the Pager notifies all targets of the next level of the escalation policy
   and sets a 15-minutes acknowledgement delay.
   */
    String serviceId = "Service1";
    String pagerAlertId = UUID.randomUUID().toString();

    Service service = new Service(serviceId, SERVICE_STATUS.UNHEALTHY);
    when(persistenceAdapter.getService(serviceId)).thenReturn(Optional.of(service));

    long someTsInThePast = System.currentTimeMillis() - (15 * 60 * 1000);
    PagerAlert pagerAlert =
        new PagerAlert(serviceId, "AlertMessage", ALERT_STATUS.OPEN, 1, someTsInThePast, someTsInThePast);
    pagerAlert.setId(pagerAlertId);
    when(persistenceAdapter.getPagerAlert(pagerAlertId)).thenReturn(Optional.of(pagerAlert));

    EscalationPolicy policy1 = new EscalationPolicy("service1",
        List.of(
            new Level(Set.of(new EmailTarget("user1@mail.com"), new SmsTarget("+34666666666"), new SmsTarget("678912345"))),
            new Level(Set.of(new EmailTarget("user2@mail.com")))));
    when(epAdapter.getEpByServiceId(serviceId)).thenReturn(Optional.of(policy1));

    alertService.acknowledgementTimeout(pagerAlertId);

    verify(mailAdapter, times(1)).sendNotification("user2@mail.com");
    verify(smsAdapter, never()).sendNotification(ArgumentMatchers.anyString());
    verify(persistenceAdapter, never()).updateService( ArgumentMatchers.any());
    verify(timerAdapter, times(1)).add15MinutesTimer(ArgumentMatchers.anyString());

    verify(persistenceAdapter, times(1)).updatePagerAlert(argThat(pagerAlert1 ->
        pagerAlert1.getLevelNotified() == 2
        && pagerAlert1.getLastNotificationTs() > someTsInThePast));
    verify(persistenceAdapter, never()).addAlertingService(any());
    verify(persistenceAdapter, never()).removeAlertingService(any());
    verify(persistenceAdapter, times(1)).addAckSempahore(pagerAlertId);
    verify(persistenceAdapter, times(1)).removeAckSemaphore(pagerAlertId);
  }


  @Test
  public void testScenario3() throws ServiceAlertingAlreadyException, AckAlertingAlreadyException {
   /*
   Given a Monitored Service in an Unhealthy State
    when the Pager receives the Acknowledgement
    and later receives the Acknowledgement Timeout,
    then the Pager doesn't notify any Target
    and doesn't set an acknowledgement delay.
   */
    String serviceId = "Service1";
    String pagerAlertId = UUID.randomUUID().toString();

    Service service = new Service(serviceId, SERVICE_STATUS.UNHEALTHY);
    when(persistenceAdapter.getService(serviceId)).thenReturn(Optional.of(service));

    long someTsInThePast = System.currentTimeMillis() - (15 * 60 * 1000);
    PagerAlert pagerAlert =
        new PagerAlert(serviceId, "AlertMessage", ALERT_STATUS.OPEN, 1, someTsInThePast, someTsInThePast);
    pagerAlert.setId(pagerAlertId);
    when(persistenceAdapter.getPagerAlert(pagerAlertId)).thenReturn(Optional.of(pagerAlert));

    alertService.alertAcknowledgement(pagerAlertId);
    verify(persistenceAdapter, times(1)).updatePagerAlert(argThat(pagerAlert1 ->
        pagerAlert1.getStatus() == ALERT_STATUS.ACKNOWLEDGED &&
        pagerAlert1.getLevelNotified() == 1 &&
        pagerAlert1.getAcknowledgmentTs() > 0 &&
        pagerAlert1.getLastNotificationTs() == someTsInThePast));

    alertService.acknowledgementTimeout(pagerAlertId);

    verify(mailAdapter,  never()).sendNotification(ArgumentMatchers.anyString());
    verify(smsAdapter, never()).sendNotification(ArgumentMatchers.anyString());
    verify(persistenceAdapter, never()).updateService( ArgumentMatchers.any());
    verify(timerAdapter, never()).add15MinutesTimer(ArgumentMatchers.anyString());
    verify(persistenceAdapter, never()).addAlertingService(any());
    verify(persistenceAdapter, never()).removeAlertingService(any());
    verify(persistenceAdapter, never()).addAckSempahore(any());
    verify(persistenceAdapter, never()).removeAckSemaphore(any());
  }


  @Test
  public void testScenario4() throws ServiceAlertingAlreadyException, AckAlertingAlreadyException {
   /*
  Given a Monitored Service in an Unhealthy State,
    when the Pager receives an Alert related to this Monitored Service,
    then the Pager doesn’t notify any Target
    and doesn’t set an acknowledgement delay
   */
    String serviceId = "Service1";

    Service service = new Service(serviceId, SERVICE_STATUS.UNHEALTHY);
    when(persistenceAdapter.getService(serviceId)).thenReturn(Optional.of(service));

    alertService.newAlert(new AlertEvent("AlertMessage", serviceId));

    verify(mailAdapter,  never()).sendNotification(ArgumentMatchers.anyString());
    verify(smsAdapter, never()).sendNotification(ArgumentMatchers.anyString());
    verify(persistenceAdapter, never()).updateService( ArgumentMatchers.any());
    verify(timerAdapter, never()).add15MinutesTimer(ArgumentMatchers.anyString());
    verify(persistenceAdapter, never()).updatePagerAlert(any());
    verify(persistenceAdapter, never()).updateService(any());
    verify(persistenceAdapter, never()).addAlertingService(any());
    verify(persistenceAdapter, never()).removeAlertingService(any());
    verify(persistenceAdapter, never()).addAckSempahore(any());
    verify(persistenceAdapter, never()).removeAckSemaphore(any());
  }


  @Test
  public void testScenario5() throws ServiceAlertingAlreadyException, AckAlertingAlreadyException {
   /*
    Given a Monitored Service in an Unhealthy State,
    when the Pager receives a Healthy event related to this Monitored Service
    and later receives the Acknowledgement Timeout,
    then the Monitored Service becomes Healthy,
    the Pager doesn’t notify any Target
    and doesn’t set an acknowledgement delay
   */
    String serviceId = "Service1";
    String pagerAlertId = UUID.randomUUID().toString();

    when(persistenceAdapter.getService(serviceId)).thenReturn(Optional.of(new Service(serviceId, SERVICE_STATUS.UNHEALTHY)));

    alertService.serviceHealthy(serviceId);
    verify(persistenceAdapter, times(1)).updateService(ArgumentMatchers.eq(new Service(serviceId, SERVICE_STATUS.HEALTHY)));
    verify(persistenceAdapter, times(1)).closeAllServiceAlerts(serviceId);

    long someTsInThePast = System.currentTimeMillis() - (15 * 60 * 1000);
    PagerAlert pagerAlert =
        new PagerAlert(serviceId, "AlertMessage", ALERT_STATUS.CLOSED, 1, someTsInThePast, someTsInThePast);
    pagerAlert.setId(pagerAlertId);
    alertService.acknowledgementTimeout(pagerAlertId);
    verify(mailAdapter,  never()).sendNotification(ArgumentMatchers.anyString());
    verify(smsAdapter, never()).sendNotification(ArgumentMatchers.anyString());
    verify(timerAdapter, never()).add15MinutesTimer(ArgumentMatchers.anyString());
    verify(persistenceAdapter, never()).updatePagerAlert(any());
    verify(persistenceAdapter, never()).addAlertingService(any());
    verify(persistenceAdapter, never()).removeAlertingService(any());
    verify(persistenceAdapter, never()).addAckSempahore(any());
    verify(persistenceAdapter, never()).removeAckSemaphore(any());
  }

  @Test
  public void testSemaphoreClosed() throws ServiceAlertingAlreadyException, AckAlertingAlreadyException {
    String serviceId = "service1";
    Service service = new Service(serviceId, SERVICE_STATUS.HEALTHY);
    when(persistenceAdapter.getService(serviceId)).thenReturn(Optional.of(service));
    doThrow(new ServiceAlertingAlreadyException("Duplicated PK:"+serviceId)).when(persistenceAdapter).addAlertingService(serviceId);

    alertService.newAlert(new AlertEvent("AlertMessage1", serviceId));

    verify(mailAdapter,  never()).sendNotification(ArgumentMatchers.anyString());
    verify(smsAdapter, never()).sendNotification(ArgumentMatchers.anyString());
    verify(persistenceAdapter, never()).updateService( ArgumentMatchers.any());
    verify(timerAdapter, never()).add15MinutesTimer(ArgumentMatchers.anyString());
    verify(persistenceAdapter, never()).updatePagerAlert(any());
    verify(persistenceAdapter, never()).updateService(any());
    verify(persistenceAdapter, times(1)).addAlertingService(any());
    verify(persistenceAdapter, never()).removeAlertingService(any());
    verify(persistenceAdapter, never()).addAckSempahore(any());
    verify(persistenceAdapter, never()).removeAckSemaphore(any());
  }


  @Test
  public void allLevelsNotified()  {
    String serviceId = "Service1";
    String pagerAlertId = UUID.randomUUID().toString();

    Service service = new Service(serviceId, SERVICE_STATUS.UNHEALTHY);
    when(persistenceAdapter.getService(serviceId)).thenReturn(Optional.of(service));

    long someTsInThePast = System.currentTimeMillis() - (15 * 60 * 1000);
    PagerAlert pagerAlert =
        new PagerAlert(serviceId, "AlertMessage", ALERT_STATUS.OPEN, 2, someTsInThePast, someTsInThePast);
    pagerAlert.setId(pagerAlertId);
    when(persistenceAdapter.getPagerAlert(pagerAlertId)).thenReturn(Optional.of(pagerAlert));

    EscalationPolicy policy1 = new EscalationPolicy("service1",
        List.of(
            new Level(Set.of(new EmailTarget("user1@mail.com"), new SmsTarget("+34666666666"), new SmsTarget("678912345"))),
            new Level(Set.of(new EmailTarget("user2@mail.com")))));
    when(epAdapter.getEpByServiceId(serviceId)).thenReturn(Optional.of(policy1));

    alertService.acknowledgementTimeout(pagerAlertId);

    verify(mailAdapter,  never()).sendNotification(ArgumentMatchers.anyString());
    verify(smsAdapter, never()).sendNotification(ArgumentMatchers.anyString());
    verify(persistenceAdapter, never()).updateService( ArgumentMatchers.any());
    verify(timerAdapter, times(1)).add15MinutesTimer(ArgumentMatchers.anyString()); //time is set anyway
  }
}
