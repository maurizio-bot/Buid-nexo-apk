import { Feather } from "@expo/vector-icons";
import * as Haptics from "expo-haptics";
import React from "react";
import { Platform, Pressable, StyleSheet, Text, View } from "react-native";
import { useColors } from "@/hooks/useColors";
import { NexoPeer } from "@/contexts/BLEContext";

interface Props {
  peer: NexoPeer;
  onConnect: (peer: NexoPeer) => void;
  onTrust: (peerId: string) => void;
  onRemove: (peerId: string) => void;
  isConnecting?: boolean;
}

function rssiToSignal(rssi: number): { bars: number; label: string; color: string } {
  if (rssi >= -55) return { bars: 4, label: "Excelente", color: "#10b981" };
  if (rssi >= -70) return { bars: 3, label: "Buena", color: "#3b82f6" };
  if (rssi >= -85) return { bars: 2, label: "Débil", color: "#f59e0b" };
  return { bars: 1, label: "Muy débil", color: "#ef4444" };
}

export function PeerCard({ peer, onConnect, onTrust, onRemove, isConnecting }: Props) {
  const colors = useColors();
  const signal = rssiToSignal(peer.rssi);
  const lastSeenSecs = Math.floor((Date.now() - peer.lastSeen) / 1000);
  const lastSeenStr = lastSeenSecs < 60 ? `${lastSeenSecs}s` : `${Math.floor(lastSeenSecs / 60)}m`;

  return (
    <View style={[styles.card, { backgroundColor: colors.card, borderColor: colors.border }]}>
      <View style={styles.header}>
        <View style={styles.headerLeft}>
          <View style={[styles.icon, { backgroundColor: colors.accent }]}>
            <Feather name="bluetooth" size={18} color={colors.primary} />
          </View>
          <View>
            <Text style={[styles.name, { color: colors.foreground }]}>{peer.name}</Text>
            <Text style={[styles.meta, { color: colors.mutedForeground }]}>
              {peer.rssi} dBm · {signal.label} · hace {lastSeenStr}
            </Text>
          </View>
        </View>
        <View style={styles.signalBars}>
          {[1, 2, 3, 4].map((bar) => (
            <View
              key={bar}
              style={[
                styles.bar,
                {
                  height: 4 + bar * 3,
                  backgroundColor:
                    bar <= signal.bars ? signal.color : colors.border,
                },
              ]}
            />
          ))}
        </View>
      </View>

      {peer.trusted && (
        <View style={[styles.trustedBadge, { backgroundColor: "#10b98122", borderColor: "#10b98144" }]}>
          <Feather name="shield" size={11} color="#10b981" />
          <Text style={[styles.trustedText, { color: "#10b981" }]}>DISPOSITIVO DE CONFIANZA</Text>
        </View>
      )}

      {peer.publicKeyFingerprint && (
        <View style={[styles.fingerprintRow, { backgroundColor: colors.secondary }]}>
          <Feather name="key" size={11} color={colors.mutedForeground} />
          <Text style={[styles.fingerprint, { color: colors.mutedForeground }]}>
            {peer.publicKeyFingerprint}
          </Text>
        </View>
      )}

      <View style={styles.actions}>
        <Pressable
          style={[styles.btn, styles.connectBtn, { backgroundColor: colors.primary }]}
          onPress={() => {
            if (Platform.OS !== "web") Haptics.impactAsync(Haptics.ImpactFeedbackStyle.Medium);
            onConnect(peer);
          }}
          disabled={isConnecting}
        >
          <Feather name="link" size={14} color={colors.primaryForeground} />
          <Text style={[styles.btnText, { color: colors.primaryForeground }]}>
            {isConnecting ? "Conectando..." : "Conectar"}
          </Text>
        </Pressable>

        {!peer.trusted && (
          <Pressable
            style={[styles.btn, { backgroundColor: colors.secondary, borderColor: colors.border, borderWidth: 1 }]}
            onPress={() => onTrust(peer.id)}
          >
            <Feather name="shield" size={14} color={colors.mutedForeground} />
          </Pressable>
        )}

        <Pressable
          style={[styles.btn, { backgroundColor: colors.secondary, borderColor: colors.border, borderWidth: 1 }]}
          onPress={() => onRemove(peer.id)}
        >
          <Feather name="trash-2" size={14} color={colors.destructive} />
        </Pressable>
      </View>
    </View>
  );
}

const styles = StyleSheet.create({
  card: {
    borderRadius: 14,
    borderWidth: 1,
    padding: 14,
    gap: 10,
    marginBottom: 10,
  },
  header: {
    flexDirection: "row",
    alignItems: "center",
    justifyContent: "space-between",
  },
  headerLeft: {
    flexDirection: "row",
    alignItems: "center",
    gap: 10,
    flex: 1,
  },
  icon: {
    width: 38,
    height: 38,
    borderRadius: 10,
    alignItems: "center",
    justifyContent: "center",
  },
  name: {
    fontSize: 15,
    fontWeight: "700",
  },
  meta: {
    fontSize: 12,
    marginTop: 1,
  },
  signalBars: {
    flexDirection: "row",
    alignItems: "flex-end",
    gap: 2,
  },
  bar: {
    width: 5,
    borderRadius: 2,
  },
  trustedBadge: {
    flexDirection: "row",
    alignItems: "center",
    gap: 5,
    paddingHorizontal: 8,
    paddingVertical: 4,
    borderRadius: 6,
    borderWidth: 1,
    alignSelf: "flex-start",
  },
  trustedText: {
    fontSize: 10,
    fontWeight: "700",
    letterSpacing: 0.8,
  },
  fingerprintRow: {
    flexDirection: "row",
    alignItems: "center",
    gap: 6,
    paddingHorizontal: 10,
    paddingVertical: 6,
    borderRadius: 8,
  },
  fingerprint: {
    fontSize: 11,
    fontFamily: "monospace",
  },
  actions: {
    flexDirection: "row",
    gap: 8,
    marginTop: 2,
  },
  btn: {
    flexDirection: "row",
    alignItems: "center",
    gap: 6,
    paddingHorizontal: 12,
    paddingVertical: 8,
    borderRadius: 8,
  },
  connectBtn: {
    flex: 1,
    justifyContent: "center",
  },
  btnText: {
    fontSize: 13,
    fontWeight: "600",
  },
});
