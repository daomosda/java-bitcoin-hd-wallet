/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package com.bitcoin.hdwallet.realtimeblocks;

/**
 *
 * @author DAOMOSDA
 */

/**
 * ZMQ endpoint configuration.
 * Read from bitcoin.conf or config.properties.
 */
public record ZmqConfig(
        String hashBlockEndpoint,
        String hashTxEndpoint,
        String rawBlockEndpoint,
        String rawTxEndpoint) {

    /** Default regtest configuration */
    public static ZmqConfig defaultRegtest() {
        return new ZmqConfig(
                "tcp://127.0.0.1:28334",  // hashblock
                "tcp://127.0.0.1:28335",  // hashtx
                "tcp://127.0.0.1:28332",  // rawblock
                "tcp://127.0.0.1:28333"   // rawtx
        );
    }

    /** Load from config.properties */
    public static ZmqConfig fromProperties(java.util.Properties props) {
        return new ZmqConfig(
                props.getProperty("zmq.hashblock", "tcp://127.0.0.1:28334"),
                props.getProperty("zmq.hashtx",    "tcp://127.0.0.1:28335"),
                props.getProperty("zmq.rawblock",  "tcp://127.0.0.1:28332"),
                props.getProperty("zmq.rawtx",     "tcp://127.0.0.1:28333")
        );
    }
}