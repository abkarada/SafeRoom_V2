# SafeRoom_V2

SafeRoom_V2 is an experimental peer-to-peer collaboration project built around direct communication between participants rather than relying on a central application server for normal data exchange.

The project was created to explore P2P communication, decentralized room management, file transfer, messaging, and real-time collaboration.

> **This repository is no longer actively maintained.**
>
> The project has been discontinued and is kept here primarily for archival and reference purposes.

---

## Overview

SafeRoom_V2 explores a collaboration model where participants communicate directly whenever possible.

The project includes experimental implementations of:

* Peer-to-peer communication
* P2P file transfer
* Messaging
* Voice and video communication
* Screen sharing and remote interaction
* Multi-peer rooms
* Peer discovery and connection management

The primary goal of the project was to experiment with building collaboration features on top of a P2P-oriented architecture.

---

## Architecture

SafeRoom_V2 was designed around peer-to-peer communication rather than routing all application traffic through a central service.

### Peer-to-Peer Communication

Participants establish connections with other peers and exchange application data directly.

Depending on the feature and network topology, peers can communicate individually or as part of a larger group.

### Multi-Peer Rooms

Rooms, referred to as **ZONEs** in the project, represent groups of connected participants.

The project experimented with dynamically organizing peers inside these rooms and handling peer joins, departures, and connection changes.

### P2P File Transfer

Files can be transferred directly between peers instead of first being uploaded to cloud storage.

The implementation explored:

* Chunked file transfer
* Parallel transfer between peers
* Transfer recovery
* Large file transmission without requiring application-level cloud storage

---

## Security

The project experimented with encrypted peer-to-peer communication and cryptographic identity mechanisms.

Some parts of the repository may represent prototypes or incomplete implementations and should not be treated as production-ready security software.

---

## Project Status

**Archived / No Longer Maintained**

SafeRoom_V2 is no longer under active development.

There are currently no plans to add new features, provide compatibility updates, fix bugs, or maintain the project for production use.

The repository remains public as a record of the project and as a reference for experiments involving:

* Peer-to-peer networking
* Distributed communication
* File transfer protocols
* Real-time collaboration
* Network topology management

---

## Disclaimer

SafeRoom_V2 was an experimental project and should not be considered production-ready software.

The repository may contain incomplete features, experimental protocols, outdated dependencies, or architectural decisions that were later abandoned.
