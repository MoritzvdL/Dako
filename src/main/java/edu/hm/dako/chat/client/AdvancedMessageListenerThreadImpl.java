package edu.hm.dako.chat.client;

import edu.hm.dako.chat.common.ChatPDU;
import edu.hm.dako.chat.common.ClientConversationStatus;
import edu.hm.dako.chat.common.ExceptionHandler;
import edu.hm.dako.chat.connection.Connection;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

/**
 * Thread wartet auf ankommende Nachrichten vom Server, bearbeitet diese und schickt eine Benachrichtigung,
 * wenn eine Nachricht erfolgreich empfangen wurde.
 *
 * @author Nisi
 *
 */
public class AdvancedMessageListenerThreadImpl extends AbstractMessageListenerThread {

    private static Log log = LogFactory.getLog(AdvancedMessageListenerThreadImpl.class);

    public AdvancedMessageListenerThreadImpl(ClientUserInterface userInterface,
                                             Connection con, SharedClientData sharedData) {
        super(userInterface, con, sharedData);
    }

    @Override
    protected void loginResponseAction(ChatPDU receivedPdu) {

        if(receivedPdu.getErrorCode() == ChatPDU.LOGIN_ERROR) {

            // Login hat nicht funktioniert
            log.error("Login-Response-PDU fuer Client " + receivedPdu.getUserName()
                    + " mit Login-Error empfangen");
            userInterface.setErrorMessage(
                    "Chat-Server", "Anmelden beim Server nicht erfolgreich, Benutzer "
                            + receivedPdu.getUserName() + " vermutlich schon angemeldet",
                    receivedPdu.getErrorCode());
            sharedClientData.status = ClientConversationStatus.UNREGISTERED;

            // Verbindung wird gleich geschlossen
            try {
                connection.close();
            } catch (Exception e) {
            }

        } else {
            // Login hat funktioniert
            sharedClientData.status = ClientConversationStatus.REGISTERED;

            userInterface.loginComplete();

            Thread.currentThread().setName("Listener" + "-" + sharedClientData.userName);
            log.debug(
                    "Login-Response-PDU fuer Client " + receivedPdu.getUserName() + " empfangen");
        }
    }

    @Override
    protected void loginEventAction(ChatPDU receivedPdu) {

        // Eventzaehler fuer Testzwecke erhoehen
        sharedClientData.eventCounter.getAndIncrement();
        int events = SharedClientData.loginEvents.incrementAndGet();

        log.debug(
                sharedClientData.userName + " erhaelt LoginEvent, LoginEventCounter: " + events);

        try {
            handleUserListEvent(receivedPdu);
        } catch (Exception e) {
            ExceptionHandler.logException(e);
        }
    }

    @Override
    protected void logoutResponseAction(ChatPDU receivedPdu) {

        log.debug(sharedClientData.userName + " empfaengt Logout-Response-PDU fuer Client "
                + receivedPdu.getUserName());
        sharedClientData.status = ClientConversationStatus.UNREGISTERED;

        userInterface.setSessionStatisticsCounter(sharedClientData.eventCounter.longValue(),
                sharedClientData.confirmCounter.longValue(), 0, 0, 0);

        log.debug("Vom Client gesendete Chat-Nachrichten:  "
                + sharedClientData.messageCounter.get());

        finished = true;
        userInterface.logoutComplete();
    }

    @Override
    protected void logoutEventAction(ChatPDU receivedPdu) {

        // Eventzaehler fuer Testzwecke erhoehen
        sharedClientData.eventCounter.getAndIncrement();
        int events = SharedClientData.logoutEvents.incrementAndGet();

        log.debug("LogoutEventCounter: " + events);

        try {
            handleUserListEvent(receivedPdu);
        } catch (Exception e) {
            ExceptionHandler.logException(e);
        }
    }

    @Override
    protected void chatMessageResponseAction(ChatPDU receivedPdu) {

        log.debug("Sequenznummer der Chat-Response-PDU " + receivedPdu.getUserName() + ": "
                + receivedPdu.getSequenceNumber() + ", Messagecounter: "
                + sharedClientData.messageCounter.get());

        log.debug(Thread.currentThread().getName()
                + ", Benoetigte Serverzeit gleich nach Empfang der Response-Nachricht: "
                + receivedPdu.getServerTime() + " ns = " + receivedPdu.getServerTime() / 1000000
                + " ms");

        if (receivedPdu.getSequenceNumber() == sharedClientData.messageCounter.get()) {

            // Zuletzt gemessene Serverzeit fuer das Benchmarking
            // merken
            userInterface.setLastServerTime(receivedPdu.getServerTime());

            // Naechste Chat-Nachricht darf eingegeben werden
            userInterface.setLock(false);

            log.debug(
                    "Chat-Response-PDU fuer Client " + receivedPdu.getUserName() + " empfangen");

        } else {
            log.debug("Sequenznummer der Chat-Response-PDU " + receivedPdu.getUserName()
                    + " passt nicht: " + receivedPdu.getSequenceNumber() + "/"
                    + sharedClientData.messageCounter.get());
        }
    }

    @Override
    protected void chatMessageEventAction(ChatPDU receivedPdu) {

        log.debug(
                "Chat-Message-Event-PDU von " + receivedPdu.getEventUserName() + " empfangen");

        // Eventzaehler und Confirmzaehler (ADVANCED) fuer Testzwecke erhoehen
        sharedClientData.eventCounter.getAndIncrement();
        sharedClientData.confirmCounter.getAndIncrement();

        int events = SharedClientData.messageEvents.incrementAndGet();

        log.debug("MessageEventCounter: " + events);
        log.debug("ConfirmCounter: " + sharedClientData.confirmCounter.get());

        // ADVANCED:Chat-Message-Event bestaetigen
        confirmChatMessageEvent(receivedPdu);

        // Empfangene Chat-Nachricht an User Interface zur
        // Darstellung uebergeben
        userInterface.setMessageLine(receivedPdu.getEventUserName(), receivedPdu.getMessage());
    }

    /**
     * Bestaetigung fuer Chat-Event-Message-PDU an Server senden (ADVANCED)
     *
     * @param receivedPdu
     *          Empfangene Chat-Event-Message-PDU
     * @throws Exception
     */
    private void confirmChatMessageEvent(ChatPDU receivedPdu) {
        ChatPDU responsePdu = ChatPDU.createMessageConfirm(sharedClientData.userName,
                receivedPdu);

        try {
            connection.send(responsePdu);
            log.debug("Message-Confirm-PDU fuer " + receivedPdu.getUserName()
                    + " das urspruengliche Event von " + receivedPdu.getEventUserName()
                    + " an den Server gesendet");
        } catch (Exception e) {
            ExceptionHandler.logException(e);
        }
    }

    /**
     * Bearbeitung aller vom Server ankommenden Nachrichten
     */
    public void run() {

        ChatPDU receivedPdu = null;

        log.debug("AdvancedMessageListenerThread gestartet");

        while (!finished) {

            try {
                // Naechste ankommende Nachricht empfangen
                log.debug("Auf die naechste Nachricht vom Server warten");
                receivedPdu = receive();
                log.debug("Nach receive Aufruf, ankommende PDU mit PduType = "
                        + receivedPdu.getPduType());
            } catch (Exception e) {
                finished = true;
            }

            if (receivedPdu != null) {

                switch (sharedClientData.status) {

                    case REGISTERING:

                        switch (receivedPdu.getPduType()) {

                            case LOGIN_RESPONSE:
                                // Login-Bestaetigung vom Server angekommen
                                loginResponseAction(receivedPdu);

                                break;

                            case LOGIN_EVENT:
                                // Meldung vom Server, dass sich die Liste der
                                // angemeldeten User erweitert hat
                                loginEventAction(receivedPdu);

                                break;

                            case LOGOUT_EVENT:
                                // Meldung vom Server, dass sich die Liste der
                                // angemeldeten User veraendert hat
                                logoutEventAction(receivedPdu);

                                break;

                            case CHAT_MESSAGE_EVENT:
                                // Chat-Nachricht vom Server gesendet
                                chatMessageEventAction(receivedPdu);
                                break;

                            default:
                                log.debug("Ankommende PDU im Zustand " + sharedClientData.status
                                        + " wird verworfen");
                        }
                        break;

                    case REGISTERED:

                        switch (receivedPdu.getPduType()) {

                            case CHAT_MESSAGE_RESPONSE:

                                // Die eigene zuletzt gesendete Chat-Nachricht wird vom
                                // Server bestaetigt.
                                chatMessageResponseAction(receivedPdu);
                                break;

                            case CHAT_MESSAGE_EVENT:
                                // Chat-Nachricht vom Server gesendet
                                chatMessageEventAction(receivedPdu);
                                break;

                            case LOGIN_EVENT:
                                // Meldung vom Server, dass sich die Liste der
                                // angemeldeten User erweitert hat
                                loginEventAction(receivedPdu);

                                break;

                            case LOGOUT_EVENT:
                                // Meldung vom Server, dass sich die Liste der
                                // angemeldeten User veraendert hat
                                logoutEventAction(receivedPdu);

                                break;

                            default:
                                log.debug("Ankommende PDU im Zustand " + sharedClientData.status
                                        + " wird verworfen");
                        }
                        break;

                    case UNREGISTERING:

                        switch (receivedPdu.getPduType()) {

                            case CHAT_MESSAGE_EVENT:
                                // Chat-Nachricht vom Server gesendet
                                chatMessageEventAction(receivedPdu);
                                break;

                            case LOGOUT_RESPONSE:
                                // Bestaetigung des eigenen Logout
                                logoutResponseAction(receivedPdu);
                                break;

                            case LOGIN_EVENT:
                                // Meldung vom Server, dass sich die Liste der
                                // angemeldeten User erweitert hat
                                loginEventAction(receivedPdu);

                                break;

                            case LOGOUT_EVENT:
                                // Meldung vom Server, dass sich die Liste der
                                // angemeldeten User veraendert hat
                                logoutEventAction(receivedPdu);

                                break;

                            default:
                                log.debug("Ankommende PDU im Zustand " + sharedClientData.status
                                        + " wird verworfen");
                                break;
                        }
                        break;

                    case UNREGISTERED:
                        log.debug(
                                "Ankommende PDU im Zustand " + sharedClientData.status + " wird verworfen");

                        break;

                    default:
                        log.debug("Unzulaessiger Zustand " + sharedClientData.status);
                }
            }
        }

        // Verbindung noch schliessen
        try {
            connection.close();
        } catch (Exception e) {
            ExceptionHandler.logException(e);
        }
        log.debug("Ordnungsgemaesses Ende des AdvancedMessageListener-Threads fuer User"
                + sharedClientData.userName + ", Status: " + sharedClientData.status);
    } // run
}
