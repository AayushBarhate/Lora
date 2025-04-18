# LoRa Packet Structure Documentation



---

## Packet Format

```packet
+---------------------------------------------------------------+
| Header (1 byte)                                               |
|   • Upper 4 bits: Version/Protocol ID                         |
|   • Lower 4 bits: Message Type                                |
+---------------------------------------------------------------+
| Sender ID (2 bytes)                                           |
|   • Unique sender identifier                                  |
+---------------------------------------------------------------+
| Destination ID (2 bytes)                                      |
|   • Unique destination identifier                             |
+---------------------------------------------------------------+
| Message ID (2 bytes)                                          |
|   • Unique message identifier                                 |
+---------------------------------------------------------------+
| TTL (1 byte)                                                  |
|   • Time-To-Live (hop limit)                                  |
+---------------------------------------------------------------+
| Total Length (2 bytes)                                        |
|   • Total packet length (header + DATA)                       |
+---------------------------------------------------------------+
| More Fragments (1 bit)                                        |
|   • '1' if additional fragments follow;                       |
|     if '0', omit Sequence Number                              |
+---------------------------------------------------------------+
| Sequence Number (2 bytes, conditional)                        |
|   • For ordering fragments (present if More Fragments = 1)    |
+---------------------------------------------------------------+
| Checksum (2 bytes)                                            |
|   • Error-checking (e.g., CRC)                                |
+---------------------------------------------------------------+
| DATA (variable length)                                        |
|   • Actual payload                                            |
+---------------------------------------------------------------+

```



---















## Field Descriptions

- **Header (1 byte)**
  - **Upper 4 bits:** Packet version and/or protocol ID.
  - **Lower 4 bits:** Message type (e.g., chat, repeat, ACK).

- **Sender ID (2 bytes)**
  - Unique identifier for the sending node.

- **Destination ID (2 bytes)**
  - Unique identifier for the destination node.

- **Message ID (2 bytes)**
  - Unique identifier for the message instance.

- **TTL (1 byte)**
  - Time-To-Live; decremented each hop. Packet is discarded when TTL reaches zero.

- **More Fragments (1 bit)**
  - Indicates if additional fragments follow.
  - If `0`, the Sequence Number field is omitted.

- **Sequence Number (2 bytes, conditional)**
  - Used for ordering fragments when More Fragments is `1`.

- **Total Length (2 bytes)**
  - Total length of the packet (header + DATA).

- **Checksum (2 bytes)**
  - Error-checking value (e.g., CRC) for verifying packet integrity.

- **DATA (variable length)**
  - The application payload.
  
    
  
    
  
    
  
    

# Issues we are facing currently 

| Issue            | Solution         | Solved ? |
| ---------------- | ---------------- | -------- |
| Packet Collision | CSMA/CA with CAD | No       |

