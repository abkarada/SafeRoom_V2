# SafeRoom V2

SafeRoom V2 was an experimental peer-to-peer collaboration system built to explore how far a desktop communication platform could be decentralized.

The project experimented with direct peer communication for messaging, file transfer, voice/video calls, screen sharing, room management, and persistent collaboration.

It is no longer actively developed.

Rather than treating the repository as an unfinished product, it is kept public as a record of the architectural experiments that shaped our later work in networking, transport protocols, and distributed systems.

## What We Built

SafeRoom experimented with several communication models:

- WebRTC peer-to-peer connections
- WebRTC DataChannels for messaging and file transfer
- peer discovery and signaling
- one-to-one voice and video communication
- mesh-based group calls
- chunked and recoverable file transfer
- persistent chat state
- cryptographic identity and encrypted communication experiments
- local file indexing and storage
- desktop screen sharing
- NAT traversal experiments
- custom transport and congestion-control experiments

The implementation grew into a relatively large Java desktop application with JavaFX, gRPC/Protobuf, WebRTC native bindings, SQLite, and several networking components.

## Architecture

At a high level, SafeRoom separated coordination from application data.

```text
                    Coordination / Signaling
                              |
                              v
                    +-------------------+
                    | Signaling Service |
                    +-------------------+
                         /          \
                        /            \
                       v              v
                    Peer A <-------> Peer B
                        \             /
                         \           /
                          v         v
                            Peer C
```

Whenever possible, application traffic was intended to move directly between peers rather than through a central application server.

For one-to-one communication this model worked reasonably well.

The difficulties became much more visible when the same model was extended to persistent rooms, group calls, messaging history, channels, peer churn, and larger groups.

## P2P Communication

SafeRoom used WebRTC peer connections and DataChannels for several P2P features.

The messaging implementation maintains peer-specific WebRTC connections and uses DataChannels for application data.

File transfer experiments included:

- chunked transmission
- acknowledgements and retransmission
- transfer recovery
- buffer reuse
- congestion-control experiments

Voice and video communication used separate WebRTC peer connections.

## Group Communication

Group calls eventually used a mesh topology.

For `N` participants, each participant maintains connections to the other `N - 1` peers.

```text
        A -------- B
        |\        /|
        | \      / |
        |  \    /  |
        |   \  /   |
        |    \/    |
        |    /\    |
        |   /  \   |
        |  /    \  |
        | /      \ |
        |/        \|
        C -------- D
```

This is simple and keeps media paths peer-to-peer, but the cost grows quickly as the room grows.

Each additional participant increases:

- the number of peer connections
- signaling state
- media encoding/transmission work
- upstream bandwidth requirements
- failure and reconnection state

The implementation therefore treated mesh group calls as a small-room mechanism rather than a general solution for large group communication.

Earlier iterations also experimented with dynamically structured peer topologies in an attempt to reduce full-mesh costs.

Those experiments exposed another class of problems: topology maintenance, peer churn, failure recovery, synchronization, and uneven relay responsibility.

## What We Learned

SafeRoom was particularly useful because several assumptions that looked attractive at the beginning became much less attractive once the system grew.

### 1. Platform independence has limits in real-time software

Java initially looked like a strong fit for the desktop client:

- memory safety
- mature concurrency primitives
- a large ecosystem
- one primary application codebase across operating systems

For conventional application logic, those advantages were real.

Real-time communication, however, depended heavily on native components such as WebRTC, media capture, rendering, screen sharing, and platform-specific system APIs.

As those components accumulated, "platform independent" increasingly meant maintaining a common Java layer above different native implementations rather than receiving platform parity for free.

For example, the project eventually required platform-specific WebRTC and SQLite runtime dependencies, while some functionality such as screen sharing had different support depending on the operating system.

The lesson was not that Java is inherently unsuitable for networking or low-latency software.

Instead, for this particular project, a managed desktop runtime combined with native media bindings made latency behavior, packaging, resource control, and platform-specific debugging harder than we wanted for the lowest layers of the system.

This influenced later work toward keeping latency-sensitive transport and media components closer to native/system-level code.

### 2. Memory safety and deterministic resource behavior are different goals

Automatic memory management removed an important class of memory-safety problems.

At the same time, the project taught us that memory safety alone does not provide deterministic execution behavior.

For latency-sensitive paths, allocation patterns, garbage collection, JNI/native transitions, buffering, thread scheduling, and media callbacks all become part of the latency budget.

SafeRoom did not require hard real-time guarantees, but trying to reason about tail latency made us increasingly interested in architectures where memory lifetime and allocation behavior could be controlled more explicitly.

This was one of the reasons later systems experiments moved toward Rust and lower-level transport implementations.

### 3. P2P works best when the problem actually has peer-local semantics

Direct communication is very attractive for operations naturally involving two endpoints:

- one-to-one calls
- direct file transfer
- ephemeral peer sessions

The model becomes significantly harder when the product requires shared persistent state:

- group conversations
- message history
- channels
- offline delivery
- membership state
- permissions
- synchronization after reconnection
- multi-device identity
- large rooms

At that point, removing centralized coordination does not remove the coordination problem.

It moves that problem into the peer network.

SafeRoom initially attempted to solve increasingly large portions of the collaboration model through peer topology and synchronization mechanisms. As the project grew, the complexity of maintaining consistent state under joins, disconnects, NAT differences, failures, and topology changes became one of the dominant architectural costs.

### 4. Decentralization is not automatically scalability

One of the original assumptions was that distributing work among peers would naturally make the system scale.

That turned out to be incomplete.

A full mesh avoids a centralized media node, but connection and bandwidth costs grow with the number of participants.

A tree or relay topology reduces some of those costs but introduces other problems:

- relay selection
- uneven bandwidth requirements
- topology repair
- peer churn
- added hops
- failure propagation
- synchronization complexity

The broader lesson was that decentralization changes where scalability costs appear; it does not eliminate them.

### 5. Hybrid architectures are often more practical

By the end of the project, the clearest architectural lesson was that the choice is rarely simply:

```text
centralized
    vs.
peer-to-peer
```

Different parts of a communication system have different requirements.

A practical architecture may use:

- direct P2P paths where they provide a clear benefit
- centralized or federated state where consistency and availability matter
- relay/SFU infrastructure where group media requires it
- dedicated transport components for latency-sensitive traffic

SafeRoom attempted to make P2P the foundation of nearly every collaboration feature.

That experiment was valuable precisely because it showed where that abstraction stopped fitting the problem.

## Technology Stack

The repository includes work with:

- Java 21
- JavaFX
- WebRTC
- WebRTC DataChannels
- gRPC
- Protocol Buffers
- SQLite
- Bouncy Castle
- UDP/STUN/UPnP experiments
- native platform integrations

The application also contains experimental networking and file-transfer components developed while exploring transport behavior.

## Repository Structure

Some of the major areas include:

```text
src/main/java/com/saferoom/

chat/             Persistent messaging experiments
crypto/           Cryptographic utilities
file_transfer/    Chunked transfer and recovery mechanisms
filevault/        Local file indexing and storage
grpc/             Coordination and signaling communication
p2p/              WebRTC DataChannel messaging and file transfer
webrtc/           Voice/video, group calls and media handling
gui/              JavaFX desktop interface
```

The repository also contains platform-specific/native integration code and Protocol Buffer definitions.

## Project Status

**Discontinued / Archived**

SafeRoom V2 is no longer being developed as a product.

The decision to stop development was primarily architectural rather than the result of a single implementation bug.

The project had grown into an increasingly complex combination of desktop UI, native media dependencies, peer topology management, distributed state, networking, storage, and transport logic.

Continuing to add features would have increased that complexity without resolving the underlying architectural trade-offs.

The project was therefore discontinued and its lessons were carried into later work focused more narrowly on networking, transport protocols, distributed systems, and secure communication.

## Notes

This repository contains experimental and incomplete implementations.

In particular:

- security-related code should not be treated as production security software
- networking components may contain simplified protocol assumptions
- platform behavior may differ
- some features represent abandoned architectural experiments
- dependencies and build instructions may be outdated

The repository is preserved for educational and historical reference.

## License

See [LICENSE](LICENSE).
