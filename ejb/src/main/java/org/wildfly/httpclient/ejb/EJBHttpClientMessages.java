package org.wildfly.httpclient.ejb;

import java.io.IOException;

import org.jboss.ejb.client.EJBClientContextIdentifier;
import org.jboss.logging.BasicLogger;
import org.jboss.logging.Logger;
import org.jboss.logging.annotations.Cause;
import org.jboss.logging.annotations.LogMessage;
import org.jboss.logging.annotations.Message;
import org.jboss.logging.annotations.MessageLogger;
import io.undertow.client.ClientResponse;

@MessageLogger(projectCode = "WFHTTPEJB")
interface EJBHttpClientMessages extends BasicLogger {

    EJBHttpClientMessages MESSAGES = Logger.getMessageLogger(EJBHttpClientMessages.class, EJBHttpClientMessages.class.getPackage().getName());

    @Message(id = 1, value = "Invalid response type %s")
    IOException invalidResponseType(String type);

    @Message(id = 2, value = "Session open timed out")
    RuntimeException sessionOpenTimedOut();

    @Message(id = 3, value = "Unexpected data in response")
    IOException unexpectedDataInResponse();

    @Message(id = 4, value = "Failed to acquire session")
    @LogMessage(level = Logger.Level.ERROR)
    void failedToAcquireSession(@Cause Throwable t);

    @Message(id = 5, value = "An EJB client context is already registered for EJB client context identifier %s")
    IllegalStateException ejbClientContextAlreadyRegisteredForIdentifier(EJBClientContextIdentifier identifier);

    @Message(id = 6, value = "No URI provided for connection %s")
    @LogMessage(level = Logger.Level.ERROR)
    void uriCannotBeNull(String conn);

    @Message(id = 7, value = "Failed to parse URI %s for connection %s")
    RuntimeException failedToParseURI(String uri, String conn);

    @Message(id = 8, value = "No modules specified for connection %s")
    RuntimeException noModulesSelectedForConnection(String conn);

    @Message(id = 9, value = "Invalid app:module:distinct specification %s for connection %s")
    RuntimeException invalidModuleSpec(String mod, String conn);

    @Message(id = 10, value = "Invalid response code %s (full response %s)")
    IOException invalidResponseCode(int responseCode, ClientResponse response);

    @Message(id = 11, value = "No session id in response")
    IOException noSessionIdInResponse();
}