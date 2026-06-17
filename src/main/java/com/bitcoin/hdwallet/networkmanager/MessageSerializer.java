/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package com.bitcoin.hdwallet.networkmanager;

/**
 *
 * @author DAOMOSDA
 */

import com.bitcoin.hdwallet.model.Message;
import org.json.JSONObject;

/**
 * Serializes Message objects to JSON strings and back.
 *
 * Wire format (one line per message):
 * {"type":"LN_PUBKEY","payload":"03abc...","senderId":"node-1"}
 */
public final class MessageSerializer {

    private MessageSerializer() {}

    public static String serialize(Message message) {
        JSONObject obj = new JSONObject();
        obj.put("type",     message.getType().name());
        obj.put("payload",  message.getPayload() != null
                                ? message.getPayload() : "");
        obj.put("senderId", message.getSenderId());
        return obj.toString();
    }

    public static Message deserialize(String json) {
        JSONObject     obj     = new JSONObject(json);
        Message.Type   type    = Message.Type.valueOf(obj.getString("type"));
        String         payload = obj.optString("payload", "");
        String         sender  = obj.optString("senderId", "unknown");
        return new Message(type, payload, sender);
    }
}
