package org.keycloak.services.clientpolicy.executor;

import java.util.Objects;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.jboss.logging.Logger;
import org.keycloak.common.util.Time;
import org.keycloak.models.ClientModel;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.utils.KeycloakModelUtils;
import org.keycloak.protocol.oidc.OIDCClientSecretConfigWrapper;
import org.keycloak.representations.idm.ClientPolicyExecutorConfigurationRepresentation;
import org.keycloak.services.clientpolicy.ClientPolicyContext;
import org.keycloak.services.clientpolicy.ClientPolicyException;
import org.keycloak.services.clientpolicy.context.ClientCRUDContext;
import org.keycloak.services.clientpolicy.context.ClientSecretRotationContext;

import static org.keycloak.services.clientpolicy.executor.ClientSecretRotationExecutorFactory.DEFAULT_SECRET_EXPIRATION_PERIOD;
import static org.keycloak.services.clientpolicy.executor.ClientSecretRotationExecutorFactory.DEFAULT_SECRET_REMAINING_ROTATION_PERIOD;
import static org.keycloak.services.clientpolicy.executor.ClientSecretRotationExecutorFactory.DEFAULT_SECRET_ROTATED_EXPIRATION_PERIOD;


/**
 * @author <a href="mailto:masales@redhat.com">Marcelo Sales</a>
 */
public class ClientSecretRotationExecutor implements
        ClientPolicyExecutorProvider<ClientSecretRotationExecutor.Configuration> {

    private static final Logger logger = Logger.getLogger(ClientSecretRotationExecutor.class);
    private final KeycloakSession session;
    private Configuration configuration;

    public ClientSecretRotationExecutor(KeycloakSession session) {
        this.session = session;
    }

    @Override
    public String getProviderId() {
        return ClientSecretRotationExecutorFactory.PROVIDER_ID;
    }

    @Override
    public Class<Configuration> getExecutorConfigurationClass() {
        return Configuration.class;
    }

    @Override
    public void executeOnEvent(ClientPolicyContext context) throws ClientPolicyException {
        if (!session.getContext().getClient().isPublicClient() && !session.getContext().getClient()
                .isBearerOnly()) {

            switch (context.getEvent()) {
                case REGISTERED:
                case UPDATED:
                    executeOnClientCreateOrUpdate((ClientCRUDContext) context);
                    break;

                case AUTHORIZATION_REQUEST:
                case TOKEN_REQUEST:
                    executeOnAuthRequest();
                    return;

                default:
                    return;
            }
        }
    }

    @Override
    public void setupConfiguration(ClientSecretRotationExecutor.Configuration config) {

        if (config == null) {
            configuration = new Configuration().parseWithDefaultValues();
        } else {
            configuration = config.parseWithDefaultValues();
        }

    }

    private void executeOnAuthRequest() {
        ClientModel client = session.getContext().getClient();
        OIDCClientSecretConfigWrapper wrapper = OIDCClientSecretConfigWrapper.fromClientModel(
                client);

        if (!wrapper.hasClientSecretExpirationTime()) {
            //first login with policy
            updatedSecretExpiration(wrapper);
        }

    }

    private void executeOnClientCreateOrUpdate(ClientCRUDContext adminContext) {
        OIDCClientSecretConfigWrapper clientConfigWrapper = OIDCClientSecretConfigWrapper.fromClientModel(
                adminContext.getTargetClient());
        logger.debugv("Executing policy {0} for client {1}-{2} with configuration [ expirationPeriod: {3}, rotatedExpirationPeriod: {4}, remainExpirationPeriod: {5} ]", getName(), clientConfigWrapper.getId(), clientConfigWrapper.getName(), configuration.getExpirationPeriod(), configuration.getRotatedExpirationPeriod(), configuration.getRemainExpirationPeriod());
        if (adminContext instanceof ClientSecretRotationContext
                || clientConfigWrapper.isClientSecretExpired()
                || !clientConfigWrapper.hasClientSecretExpirationTime()) {
            rotateSecret(adminContext, clientConfigWrapper);
        } else {
            //TODO validation for client dynamic registration

            int secondsRemaining = clientConfigWrapper.getClientSecretExpirationTime()
                    - configuration.remainExpirationPeriod;
            if (secondsRemaining <= configuration.remainExpirationPeriod) {
//        rotateSecret(adminContext);
            }
        }
    }

    private void rotateSecret(ClientCRUDContext crudContext,
                              OIDCClientSecretConfigWrapper clientConfigWrapper) {

        if (crudContext instanceof ClientSecretRotationContext) {
            ClientSecretRotationContext secretRotationContext = ((ClientSecretRotationContext) crudContext);
            if (secretRotationContext.isForceRotation()) {
                logger.debugv("Force rotation for client {0}", clientConfigWrapper.getId());
                updateRotateSecret(clientConfigWrapper, secretRotationContext.getCurrentSecret());
                updateClientConfigProperties(clientConfigWrapper);
            }
        } else if (!clientConfigWrapper.hasClientSecretExpirationTime()) {
            logger.debugv("client {0} has no secret rotation expiration time configured",clientConfigWrapper.getId());
            updatedSecretExpiration(clientConfigWrapper);
        } else {
            logger.debugv("Execute typical secret rotation for client {0}",clientConfigWrapper.getId());
            updatedSecretExpiration(clientConfigWrapper);
            updateRotateSecret(clientConfigWrapper, clientConfigWrapper.getSecret());
            KeycloakModelUtils.generateSecret(crudContext.getTargetClient());
            updateClientConfigProperties(clientConfigWrapper);
        }

        if (Objects.nonNull(crudContext.getProposedClientRepresentation())) {
            clientConfigWrapper.updateClientRepresentationAttributes(
                    crudContext.getProposedClientRepresentation());
        }

        logger.debugv("Client configured: {0}",clientConfigWrapper.toJson());
    }

    private void updatedSecretExpiration(OIDCClientSecretConfigWrapper clientConfigWrapper) {
        clientConfigWrapper.setClientSecretExpirationTime(
                Time.currentTime() + configuration.getExpirationPeriod());
        logger.debugv("A new secret expiration is configured for client {0}. Expires at {1}", clientConfigWrapper.getId(), Time.toDate(clientConfigWrapper.getClientSecretExpirationTime()));
    }

    private void updateClientConfigProperties(OIDCClientSecretConfigWrapper clientConfigWrapper) {
        clientConfigWrapper.setClientSecretCreationTime(Time.currentTime());
        updatedSecretExpiration(clientConfigWrapper);
    }

    private void updateRotateSecret(OIDCClientSecretConfigWrapper clientConfigWrapper,
                                    String secret) {
        if (configuration.rotatedExpirationPeriod > 0) {
            clientConfigWrapper.setClientRotatedSecret(secret);
            clientConfigWrapper.setClientRotatedSecretCreationTime();
            clientConfigWrapper.setClientRotatedSecretExpirationTime(
                    Time.currentTime() + configuration.getRotatedExpirationPeriod());
            logger.debugv("Rotating the secret for client {0}. Secret creation at {1}. Secret expiration at {2}", clientConfigWrapper.getId(), Time.toDate(clientConfigWrapper.getClientRotatedSecretCreationTime()), Time.toDate(clientConfigWrapper.getClientRotatedSecretExpirationTime()));
        } else {
            logger.debugv("Removing rotation for client {0}", clientConfigWrapper.getId());
            clientConfigWrapper.setClientRotatedSecret(null);
            clientConfigWrapper.setClientRotatedSecretCreationTime(null);
            clientConfigWrapper.setClientRotatedSecretExpirationTime(null);
        }
    }

    public static class Configuration extends ClientPolicyExecutorConfigurationRepresentation {

        @JsonProperty(ClientSecretRotationExecutorFactory.SECRET_EXPIRATION_PERIOD)
        protected Integer expirationPeriod;
        @JsonProperty(ClientSecretRotationExecutorFactory.SECRET_REMAINING_ROTATION_PERIOD)
        protected Integer remainExpirationPeriod;
        @JsonProperty(ClientSecretRotationExecutorFactory.SECRET_ROTATED_EXPIRATION_PERIOD)
        private Integer rotatedExpirationPeriod;

        @Override
        public boolean validateConfig() {
            logger.debugv("Validating configuration: [ expirationPeriod: {0}, rotatedExpirationPeriod: {1}, remainExpirationPeriod: {2} ]", expirationPeriod, rotatedExpirationPeriod, remainExpirationPeriod);
            // expiration must be a positive value greater than 0 (seconds)
            if (expirationPeriod <= 0) {
                return false;
            }

            // rotated secret duration could not be bigger than the main secret
            if (rotatedExpirationPeriod > expirationPeriod) {
                return false;
            }

            // remaining secret expiration period could not be bigger than main secret
            if (remainExpirationPeriod > expirationPeriod) {
                return false;
            }

            return true;
        }

        public Integer getExpirationPeriod() {
            return expirationPeriod;
        }

        public void setExpirationPeriod(Integer expirationPeriod) {
            this.expirationPeriod = expirationPeriod;
        }

        public Integer getRemainExpirationPeriod() {
            return remainExpirationPeriod;
        }

        public void setRemainExpirationPeriod(Integer remainExpirationPeriod) {
            this.remainExpirationPeriod = remainExpirationPeriod;
        }

        public Integer getRotatedExpirationPeriod() {
            return rotatedExpirationPeriod;
        }

        public void setRotatedExpirationPeriod(Integer rotatedExpirationPeriod) {
            this.rotatedExpirationPeriod = rotatedExpirationPeriod;
        }

        public Configuration parseWithDefaultValues() {
            if (getExpirationPeriod() == null) {
                setExpirationPeriod(DEFAULT_SECRET_EXPIRATION_PERIOD);
            }

            if (getRemainExpirationPeriod() == null) {
                setRemainExpirationPeriod(DEFAULT_SECRET_REMAINING_ROTATION_PERIOD);
            }

            if (getRotatedExpirationPeriod() == null) {
                setRotatedExpirationPeriod(DEFAULT_SECRET_ROTATED_EXPIRATION_PERIOD);
            }

            return this;
        }
    }

}
