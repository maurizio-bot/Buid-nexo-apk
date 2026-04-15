import AsyncStorage from "@react-native-async-storage/async-storage";
import * as Haptics from "expo-haptics";
import React, {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useRef,
  useState,
} from "react";
import { Platform } from "react-native";

export type DeviceStatus = "idle" | "scanning" | "connecting" | "connected" | "advertising";

export interface NexoPeer {
  id: string;
  name: string;
  rssi: number;
  lastSeen: number;
  trusted: boolean;
  publicKeyFingerprint?: string;
}

export interface NexoMessage {
  id: string;
  peerId: string;
  peerName: string;
  content: string;
  timestamp: number;
  direction: "inbound" | "outbound";
  encrypted: boolean;
  delivered: boolean;
}

export interface BLEContextType {
  deviceStatus: DeviceStatus;
  peers: NexoPeer[];
  messages: NexoMessage[];
  connectedPeer: NexoPeer | null;
  myDeviceName: string;
  isBluetoothEnabled: boolean;
  permissionsGranted: boolean;
  startScanning: () => Promise<void>;
  stopScanning: () => void;
  startAdvertising: () => Promise<void>;
  stopAdvertising: () => void;
  connectToPeer: (peer: NexoPeer) => Promise<void>;
  disconnectFromPeer: () => void;
  sendMessage: (content: string) => Promise<void>;
  trustPeer: (peerId: string) => void;
  removePeer: (peerId: string) => void;
  requestPermissions: () => Promise<boolean>;
  clearMessages: () => void;
  setMyDeviceName: (name: string) => void;
}

const BLEContext = createContext<BLEContextType | null>(null);

export function useBLE() {
  const ctx = useContext(BLEContext);
  if (!ctx) throw new Error("useBLE must be used within BLEProvider");
  return ctx;
}

const STORAGE_KEYS = {
  PEERS: "nexo_peers",
  MESSAGES: "nexo_messages",
  DEVICE_NAME: "nexo_device_name",
  PERMISSIONS: "nexo_permissions_granted",
};

function generateId(): string {
  return Date.now().toString() + Math.random().toString(36).substr(2, 9);
}

function generateFingerprint(): string {
  const chars = "0123456789ABCDEF";
  let result = "";
  for (let i = 0; i < 16; i++) {
    if (i > 0 && i % 4 === 0) result += ":";
    result += chars[Math.floor(Math.random() * 16)];
    result += chars[Math.floor(Math.random() * 16)];
  }
  return result;
}

function simulatePeers(): NexoPeer[] {
  return [
    {
      id: "peer_001",
      name: "NEXO-Alpha",
      rssi: -52,
      lastSeen: Date.now() - 3000,
      trusted: true,
      publicKeyFingerprint: "A1B2:C3D4:E5F6:A7B8",
    },
    {
      id: "peer_002",
      name: "NEXO-Beta",
      rssi: -67,
      lastSeen: Date.now() - 8000,
      trusted: false,
      publicKeyFingerprint: "F1E2:D3C4:B5A6:9879",
    },
    {
      id: "peer_003",
      name: "NEXO-Gamma",
      rssi: -81,
      lastSeen: Date.now() - 15000,
      trusted: false,
    },
  ];
}

export function BLEProvider({ children }: { children: React.ReactNode }) {
  const [deviceStatus, setDeviceStatus] = useState<DeviceStatus>("idle");
  const [peers, setPeers] = useState<NexoPeer[]>([]);
  const [messages, setMessages] = useState<NexoMessage[]>([]);
  const [connectedPeer, setConnectedPeer] = useState<NexoPeer | null>(null);
  const [myDeviceName, setMyDeviceNameState] = useState("NEXO-Device");
  const [isBluetoothEnabled, setIsBluetoothEnabled] = useState(true);
  const [permissionsGranted, setPermissionsGranted] = useState(
    Platform.OS === "web" ? true : false
  );

  const scanTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const advertisingTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);

  useEffect(() => {
    loadStoredData();
    return () => {
      if (scanTimerRef.current) clearTimeout(scanTimerRef.current);
      if (advertisingTimerRef.current) clearTimeout(advertisingTimerRef.current);
    };
  }, []);

  async function loadStoredData() {
    try {
      const [peersRaw, messagesRaw, nameRaw, permsRaw] = await Promise.all([
        AsyncStorage.getItem(STORAGE_KEYS.PEERS),
        AsyncStorage.getItem(STORAGE_KEYS.MESSAGES),
        AsyncStorage.getItem(STORAGE_KEYS.DEVICE_NAME),
        AsyncStorage.getItem(STORAGE_KEYS.PERMISSIONS),
      ]);
      if (peersRaw) setPeers(JSON.parse(peersRaw));
      if (messagesRaw) setMessages(JSON.parse(messagesRaw));
      if (nameRaw) setMyDeviceNameState(nameRaw);
      if (permsRaw === "true") setPermissionsGranted(true);
    } catch (e) {}
  }

  async function savePeers(updated: NexoPeer[]) {
    await AsyncStorage.setItem(STORAGE_KEYS.PEERS, JSON.stringify(updated));
  }

  async function saveMessages(updated: NexoMessage[]) {
    const recent = updated.slice(-500);
    await AsyncStorage.setItem(STORAGE_KEYS.MESSAGES, JSON.stringify(recent));
  }

  const requestPermissions = useCallback(async (): Promise<boolean> => {
    if (Platform.OS === "web") {
      setPermissionsGranted(true);
      return true;
    }
    await new Promise((r) => setTimeout(r, 800));
    setPermissionsGranted(true);
    await AsyncStorage.setItem(STORAGE_KEYS.PERMISSIONS, "true");
    if (Platform.OS !== "web") Haptics.notificationAsync(Haptics.NotificationFeedbackType.Success);
    return true;
  }, []);

  const startScanning = useCallback(async () => {
    if (!permissionsGranted) {
      const granted = await requestPermissions();
      if (!granted) return;
    }
    setDeviceStatus("scanning");
    if (Platform.OS !== "web") Haptics.impactAsync(Haptics.ImpactFeedbackStyle.Medium);

    const discovered = simulatePeers();
    let index = 0;

    function discoverNext() {
      if (index < discovered.length) {
        const newPeer = discovered[index];
        setPeers((prev) => {
          const exists = prev.find((p) => p.id === newPeer.id);
          if (exists) {
            const updated = prev.map((p) =>
              p.id === newPeer.id ? { ...p, rssi: newPeer.rssi, lastSeen: Date.now() } : p
            );
            savePeers(updated);
            return updated;
          }
          const updated = [...prev, newPeer];
          savePeers(updated);
          return updated;
        });
        index++;
        scanTimerRef.current = setTimeout(discoverNext, 1200 + Math.random() * 600);
      } else {
        scanTimerRef.current = setTimeout(() => {
          setDeviceStatus("idle");
        }, 2000);
      }
    }
    discoverNext();
  }, [permissionsGranted, requestPermissions]);

  const stopScanning = useCallback(() => {
    if (scanTimerRef.current) clearTimeout(scanTimerRef.current);
    setDeviceStatus("idle");
  }, []);

  const startAdvertising = useCallback(async () => {
    if (!permissionsGranted) {
      const granted = await requestPermissions();
      if (!granted) return;
    }
    setDeviceStatus("advertising");
    if (Platform.OS !== "web") Haptics.impactAsync(Haptics.ImpactFeedbackStyle.Light);
  }, [permissionsGranted, requestPermissions]);

  const stopAdvertising = useCallback(() => {
    setDeviceStatus("idle");
  }, []);

  const connectToPeer = useCallback(async (peer: NexoPeer) => {
    setDeviceStatus("connecting");
    if (Platform.OS !== "web") Haptics.impactAsync(Haptics.ImpactFeedbackStyle.Heavy);

    await new Promise((r) => setTimeout(r, 1500));

    const fingerprint = peer.publicKeyFingerprint ?? generateFingerprint();
    const enriched: NexoPeer = { ...peer, publicKeyFingerprint: fingerprint };
    setConnectedPeer(enriched);
    setDeviceStatus("connected");

    setPeers((prev) => {
      const updated = prev.map((p) =>
        p.id === peer.id ? { ...p, lastSeen: Date.now() } : p
      );
      savePeers(updated);
      return updated;
    });

    if (Platform.OS !== "web") Haptics.notificationAsync(Haptics.NotificationFeedbackType.Success);

    const welcomeMsg: NexoMessage = {
      id: generateId(),
      peerId: peer.id,
      peerName: peer.name,
      content: "🔐 Canal E2E establecido. Comunicación segura activa.",
      timestamp: Date.now(),
      direction: "inbound",
      encrypted: true,
      delivered: true,
    };
    setMessages((prev) => {
      const updated = [...prev, welcomeMsg];
      saveMessages(updated);
      return updated;
    });
  }, []);

  const disconnectFromPeer = useCallback(() => {
    setConnectedPeer(null);
    setDeviceStatus("idle");
    if (Platform.OS !== "web") Haptics.impactAsync(Haptics.ImpactFeedbackStyle.Medium);
  }, []);

  const sendMessage = useCallback(
    async (content: string) => {
      if (!connectedPeer) return;
      const msg: NexoMessage = {
        id: generateId(),
        peerId: connectedPeer.id,
        peerName: connectedPeer.name,
        content,
        timestamp: Date.now(),
        direction: "outbound",
        encrypted: true,
        delivered: false,
      };
      setMessages((prev) => {
        const updated = [...prev, msg];
        saveMessages(updated);
        return updated;
      });

      if (Platform.OS !== "web") Haptics.impactAsync(Haptics.ImpactFeedbackStyle.Light);

      await new Promise((r) => setTimeout(r, 400 + Math.random() * 300));

      setMessages((prev) => {
        const updated = prev.map((m) =>
          m.id === msg.id ? { ...m, delivered: true } : m
        );
        saveMessages(updated);
        return updated;
      });

      setTimeout(
        async () => {
          const replies = [
            "Recibido, confirmando identidad criptográfica...",
            "ACK — paquete verificado, integridad OK.",
            "Mensaje recibido. RSSI estable en este canal.",
            "Handshake completado. Sesión segura activa.",
            `Latencia BLE: ${Math.floor(12 + Math.random() * 25)}ms`,
          ];
          const reply: NexoMessage = {
            id: generateId(),
            peerId: connectedPeer.id,
            peerName: connectedPeer.name,
            content: replies[Math.floor(Math.random() * replies.length)],
            timestamp: Date.now(),
            direction: "inbound",
            encrypted: true,
            delivered: true,
          };
          setMessages((prev) => {
            const updated = [...prev, reply];
            saveMessages(updated);
            return updated;
          });
          if (Platform.OS !== "web") Haptics.impactAsync(Haptics.ImpactFeedbackStyle.Light);
        },
        1200 + Math.random() * 800
      );
    },
    [connectedPeer]
  );

  const trustPeer = useCallback((peerId: string) => {
    setPeers((prev) => {
      const updated = prev.map((p) =>
        p.id === peerId ? { ...p, trusted: true } : p
      );
      savePeers(updated);
      return updated;
    });
    if (Platform.OS !== "web") Haptics.notificationAsync(Haptics.NotificationFeedbackType.Success);
  }, []);

  const removePeer = useCallback((peerId: string) => {
    setPeers((prev) => {
      const updated = prev.filter((p) => p.id !== peerId);
      savePeers(updated);
      return updated;
    });
    if (Platform.OS !== "web") Haptics.notificationAsync(Haptics.NotificationFeedbackType.Warning);
  }, []);

  const clearMessages = useCallback(() => {
    setMessages([]);
    AsyncStorage.removeItem(STORAGE_KEYS.MESSAGES);
  }, []);

  const setMyDeviceName = useCallback(async (name: string) => {
    setMyDeviceNameState(name);
    await AsyncStorage.setItem(STORAGE_KEYS.DEVICE_NAME, name);
  }, []);

  return (
    <BLEContext.Provider
      value={{
        deviceStatus,
        peers,
        messages,
        connectedPeer,
        myDeviceName,
        isBluetoothEnabled,
        permissionsGranted,
        startScanning,
        stopScanning,
        startAdvertising,
        stopAdvertising,
        connectToPeer,
        disconnectFromPeer,
        sendMessage,
        trustPeer,
        removePeer,
        requestPermissions,
        clearMessages,
        setMyDeviceName,
      }}
    >
      {children}
    </BLEContext.Provider>
  );
}
