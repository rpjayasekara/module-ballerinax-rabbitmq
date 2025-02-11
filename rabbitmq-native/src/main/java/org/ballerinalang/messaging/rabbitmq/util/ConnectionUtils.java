/*
 * Copyright (c) 2019, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 * WSO2 Inc. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.ballerinalang.messaging.rabbitmq.util;

import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import com.rabbitmq.client.impl.DefaultCredentialsProvider;
import io.ballerina.runtime.api.values.BDecimal;
import io.ballerina.runtime.api.values.BMap;
import io.ballerina.runtime.api.values.BString;
import org.ballerinalang.messaging.rabbitmq.RabbitMQConstants;
import org.ballerinalang.messaging.rabbitmq.RabbitMQUtils;
import org.ballerinalang.messaging.rabbitmq.observability.RabbitMQMetricsUtil;
import org.ballerinalang.messaging.rabbitmq.observability.RabbitMQObservabilityConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.math.BigDecimal;
import java.security.KeyManagementException;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.UnrecoverableKeyException;
import java.security.cert.CertificateException;
import java.util.concurrent.TimeoutException;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;

/**
 * Util class for RabbitMQ Connection handling.
 *
 * @since 0.995.0
 */
public class ConnectionUtils {
    private static final Logger LOGGER = LoggerFactory.getLogger(ConnectionUtils.class);
    private static final BigDecimal MILLISECOND_MULTIPLIER = new BigDecimal(1000);

    /**
     * Creates a RabbitMQ Connection using the given connection parameters.
     *
     * @param connectionConfig Parameters used to initialize the connection.
     * @return RabbitMQ Connection object.
     */
    public static Connection createConnection(BString host, long port, BMap<BString, Object> connectionConfig) {
        try {
            ConnectionFactory connectionFactory = new ConnectionFactory();

            // Enable TLS for the connection.
            BMap<BString, Object> secureSocket = (BMap<BString, Object>) connectionConfig.getMapValue(
                    RabbitMQConstants.RABBITMQ_CONNECTION_SECURE_SOCKET);
            if (secureSocket != null) {
                SSLContext sslContext = getSSLContext(secureSocket);
                connectionFactory.useSslProtocol(sslContext);
                if (secureSocket.getBooleanValue(RabbitMQConstants.CONNECTION_VERIFY_HOST)) {
                    connectionFactory.enableHostnameVerification();
                }
                LOGGER.info("TLS enabled for the connection.");
            }

            connectionFactory.setHost(host.getValue());

            int portInt = Math.toIntExact(port);
            connectionFactory.setPort(portInt);

            Object username = connectionConfig.get(RabbitMQConstants.RABBITMQ_CONNECTION_USER);
            if (username != null) {
                connectionFactory.setUsername(username.toString());
            }
            Object pass = connectionConfig.get(RabbitMQConstants.RABBITMQ_CONNECTION_PASS);
            if (pass != null) {
                connectionFactory.setPassword(pass.toString());
            }
            Object timeout = connectionConfig.get(RabbitMQConstants.RABBITMQ_CONNECTION_TIMEOUT);
            if (timeout != null) {
                connectionFactory.setConnectionTimeout(getTimeValuesInMillis((BDecimal) timeout));
            }
            Object handshakeTimeout = connectionConfig.get(RabbitMQConstants.RABBITMQ_CONNECTION_HANDSHAKE_TIMEOUT);
            if (handshakeTimeout != null) {
                connectionFactory.setHandshakeTimeout(getTimeValuesInMillis((BDecimal) handshakeTimeout));
            }
            Object shutdownTimeout = connectionConfig.get(RabbitMQConstants.RABBITMQ_CONNECTION_SHUTDOWN_TIMEOUT);
            if (shutdownTimeout != null) {
                connectionFactory.setShutdownTimeout(getTimeValuesInMillis((BDecimal) shutdownTimeout));
            }
            Object connectionHeartBeat = connectionConfig.get(RabbitMQConstants.RABBITMQ_CONNECTION_HEARTBEAT);
            if (connectionHeartBeat != null) {
                // Set in seconds.
                connectionFactory.setRequestedHeartbeat((int) ((BDecimal) connectionHeartBeat).intValue());
            }
            BMap<BString, Object> authConfig = (BMap<BString, Object>) connectionConfig.getMapValue(
                    RabbitMQConstants.AUTH_CONFIG);
            if (authConfig != null) {
                connectionFactory.setCredentialsProvider(new DefaultCredentialsProvider(
                        authConfig.getStringValue(RabbitMQConstants.AUTH_USERNAME).getValue(),
                        authConfig.getStringValue(RabbitMQConstants.AUTH_PASSWORD).getValue()));
            }
            Connection connection = connectionFactory.newConnection();
            RabbitMQMetricsUtil.reportNewConnection(connection);
            return connection;
        } catch (IOException | TimeoutException exception) {
            RabbitMQMetricsUtil.reportError(RabbitMQObservabilityConstants.ERROR_TYPE_CONNECTION);
            throw RabbitMQUtils.returnErrorValue(RabbitMQConstants.CREATE_CONNECTION_ERROR
                    + exception.getMessage());
        }
    }

    private static int getTimeValuesInMillis(BDecimal value) {
        BigDecimal valueInSeconds = value.decimalValue();
        return (valueInSeconds.multiply(MILLISECOND_MULTIPLIER)).intValue();
    }

    private static SSLContext getSSLContext(BMap<BString, Object> secureSocket) {
        try {
            // keystore
            KeyManagerFactory keyManagerFactory = null;
            if (secureSocket.containsKey(RabbitMQConstants.CONNECTION_KEYSTORE)) {
                @SuppressWarnings("unchecked")
                BMap<BString, Object> cryptoKeyStore =
                        (BMap<BString, Object>) secureSocket.getMapValue(RabbitMQConstants.CONNECTION_KEYSTORE);
                char[] keyPassphrase = cryptoKeyStore.getStringValue(RabbitMQConstants.KEY_STORE_PASS).getValue()
                        .toCharArray();
                String keyFilePath = cryptoKeyStore.getStringValue(RabbitMQConstants.KEY_STORE_PATH).getValue();
                KeyStore keyStore = KeyStore.getInstance(RabbitMQConstants.KEY_STORE_TYPE);
                if (keyFilePath != null) {
                    try (FileInputStream keyFileInputStream = new FileInputStream(keyFilePath)) {
                        keyStore.load(keyFileInputStream, keyPassphrase);
                    }
                } else {
                    RabbitMQMetricsUtil.reportError(RabbitMQObservabilityConstants.ERROR_TYPE_CONNECTION);
                    throw RabbitMQUtils.returnErrorValue(RabbitMQConstants.CREATE_SECURE_CONNECTION_ERROR +
                                                                 "Path for the keystore is not found.");
                }
                keyManagerFactory =
                        KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
                keyManagerFactory.init(keyStore, keyPassphrase);
            }

            // truststore
            @SuppressWarnings("unchecked")
            BMap<BString, Object> cryptoTrustStore =
                    (BMap<BString, Object>) secureSocket.getMapValue(RabbitMQConstants.CONNECTION_TRUSTORE);
            char[] trustPassphrase = cryptoTrustStore.getStringValue(RabbitMQConstants.KEY_STORE_PASS).getValue()
                    .toCharArray();
            String trustFilePath = cryptoTrustStore.getStringValue(RabbitMQConstants.KEY_STORE_PATH).getValue();
            KeyStore trustStore = KeyStore.getInstance(RabbitMQConstants.KEY_STORE_TYPE);
            if (trustFilePath != null) {
                try (FileInputStream trustFileInputStream = new FileInputStream(trustFilePath)) {
                    trustStore.load(trustFileInputStream, trustPassphrase);
                }
            } else {
                RabbitMQMetricsUtil.reportError(RabbitMQObservabilityConstants.ERROR_TYPE_CONNECTION);
                throw RabbitMQUtils.returnErrorValue("Path for the truststore is not found.");
            }
            TrustManagerFactory trustManagerFactory =
                    TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            trustManagerFactory.init(trustStore);


            // protocol
            SSLContext sslContext;
            if (secureSocket.containsKey(RabbitMQConstants.CONNECTION_PROTOCOL)) {
                @SuppressWarnings("unchecked")
                BMap<BString, Object> protocolRecord =
                        (BMap<BString, Object>) secureSocket.getMapValue(RabbitMQConstants.CONNECTION_PROTOCOL);
                String protocol = protocolRecord.getStringValue(RabbitMQConstants.CONNECTION_PROTOCOL_NAME).getValue();
                sslContext = SSLContext.getInstance(protocol);
            } else {
                sslContext = SSLContext.getDefault();
            }
            sslContext.init(keyManagerFactory != null ? keyManagerFactory.getKeyManagers() : null,
                            trustManagerFactory.getTrustManagers(), new SecureRandom());
            return sslContext;
        } catch (FileNotFoundException exception) {
            throw RabbitMQUtils.returnErrorValue(RabbitMQConstants.CREATE_SECURE_CONNECTION_ERROR +
                                                         exception.getLocalizedMessage());
        } catch (IOException exception) {
            RabbitMQMetricsUtil.reportError(RabbitMQObservabilityConstants.ERROR_TYPE_CONNECTION);
            throw RabbitMQUtils.returnErrorValue(RabbitMQConstants.CREATE_SECURE_CONNECTION_ERROR +
                                                         "I/O error occurred.");
        } catch (CertificateException exception) {
            RabbitMQMetricsUtil.reportError(RabbitMQObservabilityConstants.ERROR_TYPE_CONNECTION);
            throw RabbitMQUtils.returnErrorValue(RabbitMQConstants.CREATE_SECURE_CONNECTION_ERROR +
                                                         "Certification error occurred.");
        } catch (UnrecoverableKeyException exception) {
            RabbitMQMetricsUtil.reportError(RabbitMQObservabilityConstants.ERROR_TYPE_CONNECTION);
            throw RabbitMQUtils.returnErrorValue(RabbitMQConstants.CREATE_SECURE_CONNECTION_ERROR +
                                                         "A key in the keystore cannot be recovered.");
        } catch (NoSuchAlgorithmException exception) {
            RabbitMQMetricsUtil.reportError(RabbitMQObservabilityConstants.ERROR_TYPE_CONNECTION);
            throw RabbitMQUtils.returnErrorValue(RabbitMQConstants.CREATE_SECURE_CONNECTION_ERROR +
                         "The particular cryptographic algorithm requested is not available in the environment.");
        } catch (KeyStoreException exception) {
            RabbitMQMetricsUtil.reportError(RabbitMQObservabilityConstants.ERROR_TYPE_CONNECTION);
            throw RabbitMQUtils.returnErrorValue(RabbitMQConstants.CREATE_SECURE_CONNECTION_ERROR +
                                         "No provider supports a KeyStoreSpi implementation for this keystore type." +
                                                         exception.getLocalizedMessage());
        } catch (KeyManagementException exception) {
            RabbitMQMetricsUtil.reportError(RabbitMQObservabilityConstants.ERROR_TYPE_CONNECTION);
            throw RabbitMQUtils.returnErrorValue(RabbitMQConstants.CREATE_SECURE_CONNECTION_ERROR +
                                                         "Error occurred in an operation with key management." +
                                                         exception.getLocalizedMessage());
        }
    }

    public static boolean isClosed(Connection connection) {
        return connection == null || !connection.isOpen();
    }

    public static Object handleCloseConnection(Object closeCode, Object closeMessage, Object timeout,
                                               Connection connection) {
        boolean validTimeout = timeout != null && RabbitMQUtils.checkIfInt(timeout);
        boolean validCloseCode = (closeCode != null && RabbitMQUtils.checkIfInt(closeCode)) &&
                (closeMessage != null && RabbitMQUtils.checkIfString(closeMessage));
        try {
            if (validTimeout && validCloseCode) {
                connection.close(Integer.parseInt(closeCode.toString()), closeMessage.toString(),
                        Integer.parseInt(timeout.toString()));
            } else if (validTimeout) {
                connection.close(Integer.parseInt(timeout.toString()));
            } else if (validCloseCode) {
                connection.close(Integer.parseInt(closeCode.toString()), closeMessage.toString());
            } else {
                connection.close();
            }
            RabbitMQMetricsUtil.reportConnectionClose(connection);
        } catch (IOException | ArithmeticException exception) {
            RabbitMQMetricsUtil.reportError(RabbitMQObservabilityConstants.ERROR_TYPE_CONNECTION_CLOSE);
            return RabbitMQUtils.returnErrorValue("Error occurred while closing the connection: "
                    + exception.getMessage());
        }
        return null;
    }

    public static void handleAbortConnection(Object closeCode, Object closeMessage, Object timeout,
                                             Connection connection) {
        boolean validTimeout = timeout != null && RabbitMQUtils.checkIfInt(timeout);
        boolean validCloseCode = (closeCode != null && RabbitMQUtils.checkIfInt(closeCode)) &&
                (closeMessage != null && RabbitMQUtils.checkIfString(closeMessage));
        if (validTimeout && validCloseCode) {
            connection.abort(Integer.parseInt(closeCode.toString()), closeMessage.toString(),
                    Integer.parseInt(timeout.toString()));
        } else if (validTimeout) {
            connection.abort(Integer.parseInt(timeout.toString()));
        } else if (validCloseCode) {
            connection.abort(Integer.parseInt(closeCode.toString()), closeMessage.toString());
        } else {
            connection.abort();
        }
        RabbitMQMetricsUtil.reportConnectionClose(connection);
    }

    private ConnectionUtils() {
    }
}
