package net.michaelfuerst.xjcp;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import net.michaelfuerst.xjcp.protocol.ChatHistory;
import net.michaelfuerst.xjcp.protocol.Contact;
import net.michaelfuerst.xjcp.protocol.Conversation;
import net.michaelfuerst.xjcp.protocol.Event;
import net.michaelfuerst.xjcp.web.HTTPService;
import net.michaelfuerst.xjcp.web.HttpParameter;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

public class DesktopXJCP implements XJCP {
	private static final Logger LOG = LogManager.getLogger();
	
	private final Gson gson = new Gson();
	private final JsonParser parser = new JsonParser();
	private final ExecutorService threadPool;
	private final String host;
	
	private volatile long keepAliveInterval = 1000;
	private volatile long lastRequestTime = 0;
	private EventHandler handler;
	private String clientId = null;
	
	private Thread keepAliveThread;
	
	public DesktopXJCP(final String host) {
		if (host == null || host.isEmpty()) {
			throw new IllegalArgumentException("invalid host: " + host);
		}
		
		this.host = host;
		this.threadPool = Executors.newFixedThreadPool(1);
		
		keepAliveThread = new Thread(() -> {
			while (!Thread.interrupted()) {				
				try {
					long timeElapsed = System.currentTimeMillis() - lastRequestTime;
					if (timeElapsed > keepAliveInterval && clientId != null) {
						ping().get(30, TimeUnit.SECONDS);
					}
					
					long sleepTime = keepAliveInterval - (System.currentTimeMillis() - lastRequestTime);
					if (sleepTime > 0) {
						Thread.sleep(sleepTime);
					}
				} catch (InterruptedException e1) {
					break;
				} catch (TimeoutException e2) {
					LOG.error("PING timed out");
				} catch (ExecutionException e3) {
					LOG.error("Exception occured during ping: " + e3.getMessage());
				}
			}
		});
		keepAliveThread.start();
	}
	
	@Override
	public void setKeepAliveInterval(final int timeInMs) {
		if (timeInMs <= 0) {
			throw new IllegalArgumentException();
		}
		
		this.keepAliveInterval = timeInMs;
	}

	@Override
	public void setEventHandler(final EventHandler handler) {
		this.handler = handler;
	}

	@Override
	public Future<Boolean> login(final String user, final String password) {
		final GenericFuture<Boolean> future = new GenericFuture<>();
		
		final JsonObject json = new JsonObject();
		json.addProperty("user", user);
		json.addProperty("pw", password);
		
		LOG.debug("Performing login for " + user);
		
		threadPool.submit(() -> {
			try {
				lastRequestTime = System.currentTimeMillis();
				String rawResponse = HTTPService.sendPostRequest(host, new HttpParameter("msg", gson.toJson(json)));
				JsonObject response = parser.parse(rawResponse).getAsJsonObject();
				extractAndPerformEvents(response);
				
				if (response.get("id") != null) {
					this.clientId = response.get("id").getAsString();
					future.setValue(true);
					LOG.debug("Login successfull for " + user);
				} else {
					future.setValue(false);
					LOG.debug("Login failed for " + user);
				}
			} catch (Exception e) {
				future.setException(e);
			}
		});
		
		return future;
	}

	@Override
	public Future<String> sendMessage(final String conversation, final String message) {
		final GenericFuture<String> future = new GenericFuture<>();
		
		final JsonObject msg = new JsonObject();
		msg.addProperty("conversation", conversation);
		msg.addProperty("message", message);
		
		final JsonObject json = new JsonObject();
		json.addProperty("id", clientId);
		json.add("message", msg);
		
		threadPool.submit(() -> {
			try {
				lastRequestTime = System.currentTimeMillis();
				String rawResponse = HTTPService.sendPostRequest(host, new HttpParameter("msg", gson.toJson(json)));
				JsonObject response = parser.parse(rawResponse).getAsJsonObject();
				extractAndPerformEvents(response);
				
				if (response.get("onMessage") != null) {
					future.setValue(response.get("onMessage").getAsString());
				} else {
					future.setValue("");
				}
			} catch (Exception e) {
				future.setException(e);
			}
		});
		
		return future;
	}

	@Override
	public Future<List<Conversation>> requestChats() {
		final GenericFuture<List<Conversation>> future = new GenericFuture<>();
				
		final JsonObject json = new JsonObject();
		json.addProperty("id", clientId);
		json.add("getChats", new JsonObject());
		
		LOG.trace("Requested conversations");
		threadPool.submit(() -> {
			try {
				lastRequestTime = System.currentTimeMillis();
				String rawResponse = HTTPService.sendPostRequest(host, new HttpParameter("msg", gson.toJson(json)));
				JsonObject response = parser.parse(rawResponse).getAsJsonObject();
				extractAndPerformEvents(response);
				
				List<Conversation> conversations = new ArrayList<>();
				if (response.get("onGetChats") != null) {
					JsonArray chats = response.get("onGetChats").getAsJsonArray();
					chats.forEach(chat -> conversations.add(gson.fromJson(chat, Conversation.class)));
				}
				
				future.setValue(conversations);
			} catch (Exception e) {
				future.setException(e);
			}
		});
		
		return future;
	}

	@Override
	public Future<List<Contact>> requestContacts() {
		final GenericFuture<List<Contact>> future = new GenericFuture<>();
		
		final JsonObject json = new JsonObject();
		json.addProperty("id", clientId);
		json.add("getContacts", new JsonObject());
		
		threadPool.submit(() -> {
			try {
				lastRequestTime = System.currentTimeMillis();
				String rawResponse = HTTPService.sendPostRequest(host, new HttpParameter("msg", gson.toJson(json)));
				JsonObject response = parser.parse(rawResponse).getAsJsonObject();
				extractAndPerformEvents(response);
				
				List<Contact> contacts = new ArrayList<>();
				if (response.get("onGetContacts") != null) {
					JsonArray cons = response.get("onGetContacts").getAsJsonArray();
					cons.forEach(contact -> contacts.add(gson.fromJson(contact, Contact.class)));
				}
				
				future.setValue(contacts);
			} catch (Exception e) {
				future.setException(e);
			}
		});
		
		return future;
	}

	@Override
	public Future<ChatHistory> requestHistory(final String conversation, final int count) {
		final GenericFuture<ChatHistory> future = new GenericFuture<>();
		
		final JsonObject request = new JsonObject();
		request.addProperty("conversation", conversation);
		request.addProperty("count", count);
		
		final JsonObject json = new JsonObject();
		json.addProperty("id", clientId);
		json.add("getHistory", request);
		
		threadPool.submit(() -> {
			try {
				lastRequestTime = System.currentTimeMillis();
				String rawResponse = HTTPService.sendPostRequest(host, new HttpParameter("msg", gson.toJson(json)));
				JsonObject response = parser.parse(rawResponse).getAsJsonObject();
				extractAndPerformEvents(response);
				
				if (response.get("onGetHistory") != null) {
					future.setValue(gson.fromJson(response.get("onGetHistory").getAsJsonObject(), ChatHistory.class));
				} else {
					future.setValue(null);
				}
			} catch (Exception e) {
				future.setException(e);
			}
		});
		
		return future;
	}

	@Override
	public Future<Void> removeEvents(final String conversation) {
		final GenericFuture<Void> future = new GenericFuture<>();
		
		final JsonObject json = new JsonObject();
		json.addProperty("id", clientId);
		json.addProperty("removeEvent", conversation);
		
		threadPool.submit(() -> {
			try {
				lastRequestTime = System.currentTimeMillis();
				String rawResponse = HTTPService.sendPostRequest(host, new HttpParameter("msg", gson.toJson(json)));
				JsonObject response = parser.parse(rawResponse).getAsJsonObject();
				extractAndPerformEvents(response);
								
				future.setValue(null);
			} catch (Exception e) {
				future.setException(e);
			}
		});
		
		return future;
	}

	@Override
	public Future<String> createConversation(final String[] participants) {
		final GenericFuture<String> future = new GenericFuture<>();
		
		final JsonObject json = new JsonObject();
		json.addProperty("id", clientId);
		json.add("newConversation", new JsonObject());
		
		threadPool.submit(() -> {
			try {
				lastRequestTime = System.currentTimeMillis();
				String rawResponse = HTTPService.sendPostRequest(host, new HttpParameter("msg", gson.toJson(json)));
				JsonObject response = parser.parse(rawResponse).getAsJsonObject();
				extractAndPerformEvents(response);
				
				if (response.get("onNewConversation") != null) {
					future.setValue(response.get("onNewConversation").getAsString());
				} else {
					future.setValue(null);
				}
			} catch (Exception e) {
				future.setException(e);
			}
		});
		
		return future;
	}

	@Override
	public Future<String> renameConversation(final String conversation, final String name) {
		final GenericFuture<String> future = new GenericFuture<>();
		
		final JsonObject json = new JsonObject();
		json.addProperty("id", clientId);
		json.add("renameConversation", new JsonObject());
		
		threadPool.submit(() -> {
			try {
				lastRequestTime = System.currentTimeMillis();
				String rawResponse = HTTPService.sendPostRequest(host, new HttpParameter("msg", gson.toJson(json)));
				JsonObject response = parser.parse(rawResponse).getAsJsonObject();
				extractAndPerformEvents(response);
				
				if (response.get("onRenameConversation") != null) {
					future.setValue(response.get("onRenameConversation").getAsString());
				} else {
					future.setValue(null);
				}
			} catch (Exception e) {
				future.setException(e);
			}
		});
		
		return future;
	}

	@Override
	public Future<Void> renameSelf(final String name) {
		final GenericFuture<Void> future = new GenericFuture<>();
		
		final JsonObject json = new JsonObject();
		json.addProperty("id", clientId);
		json.addProperty("setName", name);
		
		threadPool.submit(() -> {
			try {
				lastRequestTime = System.currentTimeMillis();
				String rawResponse = HTTPService.sendPostRequest(host, new HttpParameter("msg", gson.toJson(json)));
				JsonObject response = parser.parse(rawResponse).getAsJsonObject();
				extractAndPerformEvents(response);
								
				future.setValue(null);
			} catch (Exception e) {
				future.setException(e);
			}
		});
		
		return future;
	}

	@Override
	public Future<String> addFriend(final String user) {
		final GenericFuture<String> future = new GenericFuture<>();
		
		final JsonObject json = new JsonObject();
		json.addProperty("id", clientId);
		json.addProperty("addFriend", user);
		
		threadPool.submit(() -> {
			try {
				lastRequestTime = System.currentTimeMillis();
				String rawResponse = HTTPService.sendPostRequest(host, new HttpParameter("msg", gson.toJson(json)));
				JsonObject response = parser.parse(rawResponse).getAsJsonObject();
				extractAndPerformEvents(response);
				
				if (response.get("onAddFriend") != null) {
					future.setValue(response.get("onAddFriend").getAsString());
				} else {
					future.setValue(null);
				}
			} catch (Exception e) {
				future.setException(e);
			}
		});
		
		return future;
	}

	@Override
	public Future<Void> setStatus(final int status) {
		final GenericFuture<Void> future = new GenericFuture<>();
		
		final JsonObject json = new JsonObject();
		json.addProperty("id", clientId);
		json.addProperty("setStatus", status);
		
		threadPool.submit(() -> {
			try {
				lastRequestTime = System.currentTimeMillis();
				String rawResponse = HTTPService.sendPostRequest(host, new HttpParameter("msg", gson.toJson(json)));
				JsonObject response = parser.parse(rawResponse).getAsJsonObject();
				extractAndPerformEvents(response);
								
				future.setValue(null);
			} catch (Exception e) {
				future.setException(e);
			}
		});
		
		return future;
	}

	@Override
	public Future<Void> setProfileImage(final BufferedImage image) {
		// TODO implement
		throw new UnsupportedOperationException();
	}

	@Override
	public Future<Void> setGroupImage(final BufferedImage image, final String conversation) {
		// TODO implement
		throw new UnsupportedOperationException();
	}

	@Override
	public Future<String> injectEvent(final String conversation, final String type,
			final String message) {
		final GenericFuture<String> future = new GenericFuture<>();
		final JsonObject request = new JsonObject();
		request.addProperty("conversation", conversation);
		request.addProperty("type", type);
		request.addProperty("message", message);

		final JsonObject json = new JsonObject();
		json.addProperty("id", clientId);
		json.add("injectEvent", request);
		
		threadPool.submit(() -> {
			try {
				lastRequestTime = System.currentTimeMillis();
				String rawResponse = HTTPService.sendPostRequest(host, new HttpParameter("msg", gson.toJson(json)));
				JsonObject response = parser.parse(rawResponse).getAsJsonObject();
				extractAndPerformEvents(response);
				
				if (response.get("onInjectEvent") != null) {
					future.setValue(response.get("onInjectEvent").getAsString());
				} else {
					future.setValue(null);
				}
			} catch (Exception e) {
				future.setException(e);
			}
		});
		
		return future;
	}

	@Override
	public void shutdown() {
		keepAliveThread.interrupt();
		threadPool.shutdown();
	}

	private Future<Void> ping() {
		final GenericFuture<Void> future = new GenericFuture<>();
		
		final JsonObject json = new JsonObject();
		json.addProperty("id", clientId);

		threadPool.submit(() -> {
			try {
				lastRequestTime = System.currentTimeMillis();
				String rawResponse = HTTPService.sendPostRequest(host, new HttpParameter("msg", gson.toJson(json)));
				JsonObject response = parser.parse(rawResponse).getAsJsonObject();
				extractAndPerformEvents(response);
					
				LOG.trace("ping response: " + rawResponse);
				
				future.setValue(null);
			} catch (Exception e) {
				future.setException(e);
			}
		});
		
		return future;
	}
	
	private void extractAndPerformEvents(final JsonObject json) {
		if (handler == null || json == null || !json.has("events")) {
			return;
		}
		
		JsonArray events = json.get("events").getAsJsonArray();
		events.forEach(e -> {
			Event event = gson.fromJson(e, Event.class);
			handler.handleEvent(event);
		});
	}
}
