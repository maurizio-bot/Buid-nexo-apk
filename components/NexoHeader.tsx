import { Feather } from "@expo/vector-icons";
import React from "react";
import { Platform, StyleSheet, Text, View } from "react-native";
import { useSafeAreaInsets } from "react-native-safe-area-context";
import { useColors } from "@/hooks/useColors";
import { NexoStatusBadge } from "./NexoStatusBadge";
import { DeviceStatus } from "@/contexts/BLEContext";

interface Props {
  title: string;
  subtitle?: string;
  status?: DeviceStatus;
  right?: React.ReactNode;
}

export function NexoHeader({ title, subtitle, status, right }: Props) {
  const colors = useColors();
  const insets = useSafeAreaInsets();
  const topPad = Platform.OS === "web" ? 67 : insets.top;

  return (
    <View
      style={[
        styles.container,
        {
          backgroundColor: colors.background,
          borderBottomColor: colors.border,
          paddingTop: topPad + 8,
        },
      ]}
    >
      <View style={styles.inner}>
        <View style={styles.left}>
          <View style={styles.logoRow}>
            <Feather name="bluetooth" size={18} color={colors.primary} />
            <Text style={[styles.title, { color: colors.foreground }]}>{title}</Text>
          </View>
          {subtitle && (
            <Text style={[styles.subtitle, { color: colors.mutedForeground }]}>{subtitle}</Text>
          )}
        </View>
        <View style={styles.right}>
          {status && <NexoStatusBadge status={status} />}
          {right}
        </View>
      </View>
    </View>
  );
}

const styles = StyleSheet.create({
  container: {
    borderBottomWidth: 1,
    paddingBottom: 12,
    paddingHorizontal: 16,
  },
  inner: {
    flexDirection: "row",
    alignItems: "center",
    justifyContent: "space-between",
  },
  left: {
    flex: 1,
  },
  logoRow: {
    flexDirection: "row",
    alignItems: "center",
    gap: 7,
  },
  title: {
    fontSize: 20,
    fontWeight: "800",
    letterSpacing: 0.5,
  },
  subtitle: {
    fontSize: 12,
    marginTop: 2,
    marginLeft: 25,
  },
  right: {
    flexDirection: "row",
    alignItems: "center",
    gap: 8,
  },
});
