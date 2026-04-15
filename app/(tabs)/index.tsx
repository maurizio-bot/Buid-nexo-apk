import { Feather } from "@expo/vector-icons";
import * as Haptics from "expo-haptics";
import { useRouter } from "expo-router";
import React, { useState } from "react";
import {
  Platform,
  Pressable,
  ScrollView,
  StyleSheet,
  Text,
  View,
} from "react-native";
import { useSafeAreaInsets } from "react-native-safe-area-context";
import { BLERadarView } from "@/components/BLERadarView";
import { NexoHeader } from "@/components/NexoHeader";
import { NexoStatusBadge } from "@/components/NexoStatusBadge";
import { PeerCard } from "@/components/PeerCard";
import { useBLE } from "@/contexts/BLEContext";
import { useColors } from "@/hooks/useColors";

export default function ScanScreen() {
  const colors = useColors();
  const router = useRouter();
  const insets = useSafeAreaInsets();
  const bottomPad = Platform.OS === "web" ? 34 : insets.bottom;
  const {
    deviceStatus,
    peers,
    myDeviceName,
    permissionsGranted,
    startScanning,
    stopScanning,
    startAdvertising,
    stopAdvertising,
    connectToPeer,
    trustPeer,
    removePeer,
    requestPermissions,
  } = useBLE();

  const [connectingId, setConnectingId] = useState<string | null>(null);

  const isScanning = deviceStatus === "scanning";
  const isAdvertising = deviceStatus === "advertising";
  const isConnecting = deviceStatus === "connecting";
  const isConnected = deviceStatus === "connected";

  async function handleScan() {
    if (Platform.OS !== "web") Haptics.impactAsync(Haptics.ImpactFeedbackStyle.Medium);
    if (isScanning) {
      stopScanning();
    } else {
      await startScanning();
    }
  }

  async function handleAdvertise() {
    if (Platform.OS !== "web") Haptics.impactAsync(Haptics.ImpactFeedbackStyle.Medium);
    if (isAdvertising) {
      stopAdvertising();
    } else {
      await startAdvertising();
    }
  }

  async function handleConnect(peer: typeof peers[0]) {
    setConnectingId(peer.id);
    await connectToPeer(peer);
    setConnectingId(null);
    router.push("/chat");
  }

  const radarActive = isScanning || isAdvertising;

  return (
    <View style={[styles.screen, { backgroundColor: colors.background }]}>
      <NexoHeader
        title="NEXO"
        subtitle={myDeviceName}
        status={deviceStatus}
      />

      <ScrollView
        style={styles.scroll}
        contentContainerStyle={[styles.content, { paddingBottom: bottomPad + 90 }]}
        showsVerticalScrollIndicator={false}
      >
        <BLERadarView isActive={radarActive} peerCount={peers.length} />

        <View style={styles.controls}>
          <Pressable
            style={({ pressed }) => [
              styles.controlBtn,
              {
                backgroundColor: isScanning ? colors.primary : colors.card,
                borderColor: isScanning ? colors.primary : colors.border,
                opacity: pressed ? 0.8 : 1,
              },
            ]}
            onPress={handleScan}
            disabled={isConnecting || isConnected}
          >
            <Feather
              name="radio"
              size={22}
              color={isScanning ? colors.primaryForeground : colors.primary}
            />
            <Text
              style={[
                styles.controlLabel,
                { color: isScanning ? colors.primaryForeground : colors.foreground },
              ]}
            >
              {isScanning ? "Detener" : "Escanear"}
            </Text>
          </Pressable>

          <Pressable
            style={({ pressed }) => [
              styles.controlBtn,
              {
                backgroundColor: isAdvertising ? colors.info : colors.card,
                borderColor: isAdvertising ? colors.info : colors.border,
                opacity: pressed ? 0.8 : 1,
              },
            ]}
            onPress={handleAdvertise}
            disabled={isConnecting || isConnected}
          >
            <Feather
              name="wifi"
              size={22}
              color={isAdvertising ? "#fff" : colors.info}
            />
            <Text
              style={[
                styles.controlLabel,
                { color: isAdvertising ? "#fff" : colors.foreground },
              ]}
            >
              {isAdvertising ? "Ocultar" : "Anunciar"}
            </Text>
          </Pressable>
        </View>

        {!permissionsGranted && (
          <View
            style={[
              styles.permBanner,
              { backgroundColor: `${colors.warning}22`, borderColor: `${colors.warning}44` },
            ]}
          >
            <Feather name="alert-triangle" size={16} color={colors.warning} />
            <Text style={[styles.permText, { color: colors.warning }]}>
              Se requieren permisos Bluetooth
            </Text>
            <Pressable
              style={[styles.permBtn, { backgroundColor: colors.warning }]}
              onPress={requestPermissions}
            >
              <Text style={[styles.permBtnText, { color: colors.background }]}>Permitir</Text>
            </Pressable>
          </View>
        )}

        {peers.length === 0 ? (
          <View style={styles.emptyState}>
            <Feather name="bluetooth" size={36} color={colors.mutedForeground} />
            <Text style={[styles.emptyTitle, { color: colors.mutedForeground }]}>
              Sin dispositivos detectados
            </Text>
            <Text style={[styles.emptySubtitle, { color: colors.mutedForeground }]}>
              Toca Escanear para buscar peers NEXO cercanos
            </Text>
          </View>
        ) : (
          <View style={styles.peersList}>
            <Text style={[styles.sectionTitle, { color: colors.mutedForeground }]}>
              DISPOSITIVOS BLE ({peers.length})
            </Text>
            {peers.map((peer) => (
              <PeerCard
                key={peer.id}
                peer={peer}
                onConnect={handleConnect}
                onTrust={trustPeer}
                onRemove={removePeer}
                isConnecting={connectingId === peer.id}
              />
            ))}
          </View>
        )}
      </ScrollView>
    </View>
  );
}

const styles = StyleSheet.create({
  screen: {
    flex: 1,
  },
  scroll: {
    flex: 1,
  },
  content: {
    padding: 16,
    gap: 20,
  },
  controls: {
    flexDirection: "row",
    gap: 12,
  },
  controlBtn: {
    flex: 1,
    alignItems: "center",
    justifyContent: "center",
    paddingVertical: 16,
    borderRadius: 14,
    borderWidth: 1.5,
    gap: 6,
  },
  controlLabel: {
    fontSize: 13,
    fontWeight: "600",
  },
  permBanner: {
    flexDirection: "row",
    alignItems: "center",
    gap: 8,
    padding: 12,
    borderRadius: 10,
    borderWidth: 1,
  },
  permText: {
    flex: 1,
    fontSize: 13,
    fontWeight: "600",
  },
  permBtn: {
    paddingHorizontal: 12,
    paddingVertical: 6,
    borderRadius: 8,
  },
  permBtnText: {
    fontSize: 12,
    fontWeight: "700",
  },
  emptyState: {
    alignItems: "center",
    paddingVertical: 32,
    gap: 10,
  },
  emptyTitle: {
    fontSize: 16,
    fontWeight: "600",
    marginTop: 8,
  },
  emptySubtitle: {
    fontSize: 13,
    textAlign: "center",
    paddingHorizontal: 20,
  },
  peersList: {
    gap: 0,
  },
  sectionTitle: {
    fontSize: 11,
    fontWeight: "700",
    letterSpacing: 1,
    marginBottom: 10,
  },
});
