import React, { useEffect, useRef } from "react";
import { Animated, StyleSheet, Text, View } from "react-native";
import { useColors } from "@/hooks/useColors";
import { DeviceStatus } from "@/contexts/BLEContext";

interface Props {
  status: DeviceStatus;
}

const STATUS_LABELS: Record<DeviceStatus, string> = {
  idle: "INACTIVO",
  scanning: "ESCANEANDO",
  connecting: "CONECTANDO",
  connected: "CONECTADO",
  advertising: "ANUNCIANDO",
};

const STATUS_COLORS: Record<DeviceStatus, string> = {
  idle: "#64748b",
  scanning: "#3b82f6",
  connecting: "#f59e0b",
  connected: "#10b981",
  advertising: "#00d4ff",
};

export function NexoStatusBadge({ status }: Props) {
  const colors = useColors();
  const pulseAnim = useRef(new Animated.Value(1)).current;

  const isAnimating = status === "scanning" || status === "advertising" || status === "connecting";

  useEffect(() => {
    if (isAnimating) {
      const loop = Animated.loop(
        Animated.sequence([
          Animated.timing(pulseAnim, { toValue: 0.3, duration: 700, useNativeDriver: true }),
          Animated.timing(pulseAnim, { toValue: 1, duration: 700, useNativeDriver: true }),
        ])
      );
      loop.start();
      return () => loop.stop();
    } else {
      pulseAnim.setValue(1);
    }
  }, [isAnimating, pulseAnim]);

  const dotColor = STATUS_COLORS[status];

  return (
    <View style={[styles.container, { backgroundColor: `${dotColor}22`, borderColor: `${dotColor}44` }]}>
      <Animated.View style={[styles.dot, { backgroundColor: dotColor, opacity: pulseAnim }]} />
      <Text style={[styles.label, { color: dotColor }]}>{STATUS_LABELS[status]}</Text>
    </View>
  );
}

const styles = StyleSheet.create({
  container: {
    flexDirection: "row",
    alignItems: "center",
    gap: 6,
    paddingHorizontal: 12,
    paddingVertical: 5,
    borderRadius: 20,
    borderWidth: 1,
  },
  dot: {
    width: 7,
    height: 7,
    borderRadius: 4,
  },
  label: {
    fontSize: 11,
    fontWeight: "700",
    letterSpacing: 1,
  },
});
