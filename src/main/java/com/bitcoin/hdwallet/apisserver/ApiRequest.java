package com.bitcoin.hdwallet.apisserver;

/**
 *
 * @author DAOMOSDA
 */

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Represents an API request for receiving Bitcoin
 */
public class ApiRequest {
    private double amountBtc;
    private String address;
    private String label;
    
    /**
     * Parses JSON string into ApiRequest object
     * 
     * Expected JSON format:
     * {
     *   "amountBtc": 0.001,
     *   "address": "bc1q...",   // optional
     *   "label": "payment-123"  // optional
     * }
     * 
     * @param body JSON string containing request parameters
     * @return Parsed ApiRequest object
     * @throws RuntimeException if JSON is invalid or parsing fails
     */
    public static ApiRequest fromJson(String body) {
        if (body == null || body.trim().isEmpty()) {
            throw new IllegalArgumentException("JSON body cannot be null or empty");
        }
        
        try {
            ObjectMapper mapper = new ObjectMapper();
            JsonNode rootNode = mapper.readTree(body);
            
            ApiRequest request = new ApiRequest();
            
            // Parse amountBtc (required)
            if (rootNode.has("amountBtc")) {
                JsonNode amountNode = rootNode.get("amountBtc");
                if (amountNode.isNumber()) {
                    request.setAmountBtc(amountNode.asDouble());
                } else if (amountNode.isTextual()) {
                    // Handle string numbers like "0.001"
                    request.setAmountBtc(Double.parseDouble(amountNode.asText()));
                } else {
                    throw new IllegalArgumentException("amountBtc must be a number");
                }
            } else {
                throw new IllegalArgumentException("amountBtc is required");
            }
            
            // Parse address (optional)
            if (rootNode.has("address")) {
                request.setAddress(rootNode.get("address").asText());
            }
            
            // Parse label (optional)
            if (rootNode.has("label")) {
                request.setLabel(rootNode.get("label").asText());
            }
            
            return request;
            
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new RuntimeException("Invalid JSON format: " + e.getMessage(), e);
        } catch (NumberFormatException e) {
            throw new RuntimeException("Invalid number format for amountBtc: " + e.getMessage(), e);
        }
    }
    
    // Getters and setters
    public double getAmountBtc() { return amountBtc; }
    public void setAmountBtc(double amountBtc) { this.amountBtc = amountBtc; }
    
    public String getAddress() { return address; }
    public void setAddress(String address) { this.address = address; }
    
    public String getLabel() { return label; }
    public void setLabel(String label) { this.label = label; }
    
    @Override
    public String toString() {
        return String.format(
            "ApiRequest{amountBtc=%.8f, address='%s', label='%s'}",
            amountBtc, address, label
        );
    }
}