package eu.neclab.ngsildbroker.registry.subscriptionmanager.service;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import eu.neclab.ngsildbroker.commons.constants.NGSIConstants;
import eu.neclab.ngsildbroker.commons.datatypes.Notification;
import eu.neclab.ngsildbroker.commons.datatypes.Subscription;
import eu.neclab.ngsildbroker.commons.datatypes.requests.BaseRequest;
import eu.neclab.ngsildbroker.commons.datatypes.requests.SubscriptionRequest;
import eu.neclab.ngsildbroker.commons.exceptions.ResponseException;
import eu.neclab.ngsildbroker.commons.interfaces.NotificationHandler;
import eu.neclab.ngsildbroker.commons.subscriptionbase.BaseSubscriptionService;
import eu.neclab.ngsildbroker.commons.subscriptionbase.SubscriptionInfoDAOInterface;
import eu.neclab.ngsildbroker.commons.tools.EntityTools;

@Service
public class RegistrySubscriptionService extends BaseSubscriptionService {

	@Autowired
	@Qualifier("regsubdao")
	SubscriptionInfoDAOInterface subService;

	@Autowired
	KafkaTemplate<String, Object> kafkaTemplate;

	@Value("${scorpio.topics.internalnotification}")
	private String NOTIFICATION_TOPIC;

	private NotificationHandler internalHandler;

	private HashMap<String, SubscriptionRequest> id2InternalSubscriptions = new HashMap<String, SubscriptionRequest>();

	@PostConstruct
	private void notificationHandlerSetup() {
		this.internalHandler = new InternalNotificationHandler(kafkaTemplate, NOTIFICATION_TOPIC);
	}

	@Override
	protected SubscriptionInfoDAOInterface getSubscriptionInfoDao() {
		return subService;
	}

	@Override
	protected Set<String> getTypesFromEntry(BaseRequest createRequest) {
		return EntityTools.getRegisteredTypes(createRequest.getFinalPayload());
	}

	@Override
	protected Notification getNotification(SubscriptionRequest request, List<Map<String, Object>> dataList,
			int triggerReason) {
		return new Notification(EntityTools.getRandomID("notification:"), NGSIConstants.CSOURCE_NOTIFICATION,
				System.currentTimeMillis(), request.getSubscription().getId(), dataList, triggerReason,
				request.getContext());
	}

	@Override
	protected boolean sendInitialNotification() {
		return true;
	}

	@Override
	protected NotificationHandler getNotificationHandler(String endpointProtocol) {
		if (endpointProtocol.equals("internal")) {
			return internalHandler;
		}
		return super.getNotificationHandler(endpointProtocol);
	}

	void subscribeInternal(SubscriptionRequest request) {
		makeSubscriptionInternal(request);
		try {
			subscribe(request);
		} catch (ResponseException e) {
			logger.error("Failed to subscribe internally", e);
		}
	}

	void unsubscribeInternal(String subId) {
		SubscriptionRequest request = id2InternalSubscriptions.remove(subId);
		try {
			unsubscribe(subId, request.getHeaders());
		} catch (ResponseException e) {
			logger.error("Failed to subscribe internally", e);
		}
	}

	@PreDestroy
	@Override
	protected void deconstructor() {
		for (Entry<String, SubscriptionRequest> entry : id2InternalSubscriptions.entrySet()) {
			try {
				unsubscribe(entry.getKey(), entry.getValue().getHeaders());
			} catch (ResponseException e) {
				logger.error("Failed to subscribe internally", e);
			}
		}
		super.deconstructor();
	}

	public void updateInternal(SubscriptionRequest request) {
		makeSubscriptionInternal(request);
		try {
			updateSubscription(request);
		} catch (ResponseException e) {
			logger.error("Failed to subscribe internally", e);
		}

	}

	private void makeSubscriptionInternal(SubscriptionRequest request) {
		Subscription sub = request.getSubscription();
		try {
			if (sub.getNotification() != null) {
				sub.getNotification().getEndPoint().setUri(new URI("internal://kafka"));
			}
		} catch (URISyntaxException e) {
			logger.error("Failed to set internal sub endpoint", e);
		}
		id2InternalSubscriptions.put(sub.getId(), request);
	}

	@Override
	protected String generateUniqueSubId(Subscription subscription) {
		return "urn:ngsi-ld:Registry:Subscription:" + subscription.hashCode();
	}

	@Override
	protected boolean sendDeleteNotification() {
		return true;
	}
}
