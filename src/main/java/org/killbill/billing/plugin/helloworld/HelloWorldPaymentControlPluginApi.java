/*
 * POC: Simulates the Evervault control plugin for Kill Bill.
 *
 * This plugin implements PaymentControlPluginApi ONLY.
 * It intercepts two moments:
 *
 *   1. addPaymentMethodWithControl — stores the encrypted PAN (ev:... token)
 *      in an in-memory map, then returns adjustedPluginName to re-tag
 *      the payment method under the target PSP plugin (e.g. killbill-stripe).
 *
 *   2. purchasePayment / authorizePayment — retrieves the stored PAN
 *      and injects it as ccNumber in the plugin properties so the
 *      PSP plugin can use it.
 *
 * In production, the in-memory map would be replaced by a database table.
 */

package org.killbill.billing.plugin.helloworld;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.killbill.billing.control.plugin.api.OnFailurePaymentControlResult;
import org.killbill.billing.control.plugin.api.OnSuccessPaymentControlResult;
import org.killbill.billing.control.plugin.api.PaymentControlApiException;
import org.killbill.billing.control.plugin.api.PaymentControlContext;
import org.killbill.billing.control.plugin.api.PaymentControlPluginApi;
import org.killbill.billing.control.plugin.api.PriorPaymentControlResult;
import org.killbill.billing.payment.api.PluginProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class HelloWorldPaymentControlPluginApi implements PaymentControlPluginApi {

    private static final Logger logger = LoggerFactory.getLogger(HelloWorldPaymentControlPluginApi.class);

    // In-memory storage: accountId -> stored card data
    // In production, this would be a database table keyed by paymentMethodId
    private final ConcurrentHashMap<UUID, StoredCardData> cardStore = new ConcurrentHashMap<>();

    private static class StoredCardData {
        final String encPan;
        final String encCvv;
        final String expirationMonth;
        final String expirationYear;
        final String targetPlugin;

        StoredCardData(String encPan, String encCvv, String expirationMonth,
                       String expirationYear, String targetPlugin) {
            this.encPan = encPan;
            this.encCvv = encCvv;
            this.expirationMonth = expirationMonth;
            this.expirationYear = expirationYear;
            this.targetPlugin = targetPlugin;
        }
    }

    @Override
    public PriorPaymentControlResult priorCall(final PaymentControlContext context,
                                                final Iterable<PluginProperty> properties)
            throws PaymentControlApiException {

        final String encPan = getPluginProperty(properties, "encPan");

        if (encPan != null) {
            return handleAddPaymentMethod(context, properties, encPan);
        } else {
            return handlePayment(context, properties);
        }
    }

    private PriorPaymentControlResult handleAddPaymentMethod(
            final PaymentControlContext context,
            final Iterable<PluginProperty> properties,
            final String encPan) {

        final String encCvv = getPluginProperty(properties, "encCvv");
        final String expMonth = getPluginProperty(properties, "expirationMonth");
        final String expYear = getPluginProperty(properties, "expirationYear");
        final String targetPlugin = getPluginProperty(properties, "targetPlugin");

        final UUID accountId = context.getAccountId();

        final StoredCardData card = new StoredCardData(encPan, encCvv, expMonth, expYear, targetPlugin);
        cardStore.put(accountId, card);

        logger.info("[Evervault POC] Stored encrypted PAN for accountId={}. Returning adjustedPluginName={}", accountId, targetPlugin);

        return new PriorPaymentControlResult() {
            @Override
            public boolean isAborted() { return false; }

            @Override
            public java.math.BigDecimal getAdjustedAmount() { return null; }

            @Override
            public org.killbill.billing.catalog.api.Currency getAdjustedCurrency() { return null; }

            @Override
            public UUID getAdjustedPaymentMethodId() { return null; }

            @Override
            public Iterable<PluginProperty> getAdjustedPluginProperties() { return properties; }

            @Override
            public String getAdjustedPluginName() { return targetPlugin; }
        };
    }

    private PriorPaymentControlResult handlePayment(
            final PaymentControlContext context,
            final Iterable<PluginProperty> properties) {

        final UUID accountId = context.getAccountId();
        final StoredCardData card = cardStore.get(accountId);

        if (card == null) {
            logger.info("[Evervault POC] No stored card for accountId={}. Passing through.", accountId);
            return new DefaultPriorPaymentControlResult(properties);
        }

        logger.info("[Evervault POC] Injecting encrypted PAN for accountId={}. TransactionType={}", accountId, context.getTransactionType());

        final List<PluginProperty> adjustedProperties = new ArrayList<>();
        for (PluginProperty prop : properties) {
            adjustedProperties.add(prop);
        }
        adjustedProperties.add(new PluginProperty("ccNumber", card.encPan, false));
        adjustedProperties.add(new PluginProperty("ccExpirationMonth", card.expirationMonth, false));
        adjustedProperties.add(new PluginProperty("ccExpirationYear", card.expirationYear, false));
        if (card.encCvv != null) {
            adjustedProperties.add(new PluginProperty("ccVerificationValue", card.encCvv, false));
        }

        return new DefaultPriorPaymentControlResult(adjustedProperties);
    }

    @Override
    public OnSuccessPaymentControlResult onSuccessCall(final PaymentControlContext context,
                                                        final Iterable<PluginProperty> properties)
            throws PaymentControlApiException {
        logger.info("[Evervault POC] onSuccessCall for accountId={}", context.getAccountId());
        return new OnSuccessPaymentControlResult() {
            @Override
            public Iterable<PluginProperty> getAdjustedPluginProperties() { return properties; }
        };
    }

    @Override
    public OnFailurePaymentControlResult onFailureCall(final PaymentControlContext context,
                                                        final Iterable<PluginProperty> properties)
            throws PaymentControlApiException {
        logger.info("[Evervault POC] onFailureCall for accountId={}", context.getAccountId());
        return new OnFailurePaymentControlResult() {
            @Override
            public org.joda.time.DateTime getNextRetryDate() { return null; }

            @Override
            public Iterable<PluginProperty> getAdjustedPluginProperties() { return properties; }
        };
    }

    private String getPluginProperty(final Iterable<PluginProperty> properties, final String key) {
        if (properties == null) return null;
        for (PluginProperty prop : properties) {
            if (key.equals(prop.getKey())) {
                return prop.getValue() != null ? prop.getValue().toString() : null;
            }
        }
        return null;
    }

    private static class DefaultPriorPaymentControlResult implements PriorPaymentControlResult {
        private final Iterable<PluginProperty> properties;

        DefaultPriorPaymentControlResult(Iterable<PluginProperty> properties) {
            this.properties = properties;
        }

        @Override
        public boolean isAborted() { return false; }

        @Override
        public java.math.BigDecimal getAdjustedAmount() { return null; }

        @Override
        public org.killbill.billing.catalog.api.Currency getAdjustedCurrency() { return null; }

        @Override
        public UUID getAdjustedPaymentMethodId() { return null; }

        @Override
        public Iterable<PluginProperty> getAdjustedPluginProperties() { return properties; }

        @Override
        public String getAdjustedPluginName() { return null; }
    }
}