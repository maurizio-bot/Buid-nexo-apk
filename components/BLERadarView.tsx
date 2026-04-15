import React, { useEffect, useRef } from "react";
import { Animated, Easing, StyleSheet, Text, View } from "react-native";
import { useColors } from "@/hooks/useColors";

interface Props {
  isActive: boolean;
  peerCount: number;
}

export function BLERadarView({ isActive, peerCount }: Props) {
  const colors = useColors();
  const ring1 = useRef(new Animated.Value(0)).current;
  const ring2 = useRef(new Animated.Value(0)).current;
  const ring3 = useRef(new Animated.Value(0)).current;
  const rotateAnim = useRef(new Animated.Value(0)).current;

  useEffect(() => {
    if (!isActive) {
      ring1.setValue(0);
      ring2.setValue(0);
      ring3.setValue(0);
      rotateAnim.setValue(0);
      return;
    }

    const makeRing = (anim: Animated.Value, delay: number) =>
      Animated.loop(
        Animated.sequence([
          Animated.delay(delay),
          Animated.timing(anim, {
            toValue: 1,
            duration: 2200,
            easing: Easing.out(Easing.quad),
            useNativeDriver: true,
          }),
          Animated.timing(anim, { toValue: 0, duration: 0, useNativeDriver: true }),
        ])
      );

    const r1 = makeRing(ring1, 0);
    const r2 = makeRing(ring2, 700);
    const r3 = makeRing(ring3, 1400);
    const rot = Animated.loop(
      Animated.timing(rotateAnim, { toValue: 1, duration: 3500, useNativeDriver: true, easing: Easing.linear })
    );

    r1.start();
    r2.start();
    r3.start();
    rot.start();

    return () => {
      r1.stop();
      r2.stop();
      r3.stop();
      rot.stop();
    };
  }, [isActive, ring1, ring2, ring3, rotateAnim]);

  const makeRingStyle = (anim: Animated.Value) => ({
    transform: [
      {
        scale: anim.interpolate({ inputRange: [0, 1], outputRange: [0.3, 1.6] }),
      },
    ],
    opacity: anim.interpolate({ inputRange: [0, 0.4, 1], outputRange: [0.8, 0.4, 0] }),
  });

  const rotate = rotateAnim.interpolate({ inputRange: [0, 1], outputRange: ["0deg", "360deg"] });

  return (
    <View style={styles.container}>
      <Animated.View
        style={[styles.ring, { borderColor: colors.primary, transform: [{ scale: 1.5 }], opacity: 0.08 }]}
      />
      <Animated.View
        style={[styles.ring, { borderColor: colors.primary, transform: [{ scale: 1.1 }], opacity: 0.14 }]}
      />

      {isActive && (
        <>
          <Animated.View style={[styles.ring, { borderColor: colors.primary }, makeRingStyle(ring1)]} />
          <Animated.View style={[styles.ring, { borderColor: colors.primary }, makeRingStyle(ring2)]} />
          <Animated.View style={[styles.ring, { borderColor: colors.primary }, makeRingStyle(ring3)]} />
        </>
      )}

      {isActive && (
        <Animated.View
          style={[styles.sweepContainer, { transform: [{ rotate }] }]}
        >
          <View style={[styles.sweep, { backgroundColor: colors.primary }]} />
        </Animated.View>
      )}

      <View style={[styles.center, { backgroundColor: colors.card, borderColor: colors.primary }]}>
        <Text style={[styles.peerCount, { color: colors.primary }]}>{peerCount}</Text>
        <Text style={[styles.peerLabel, { color: colors.mutedForeground }]}>
          {peerCount === 1 ? "PEER" : "PEERS"}
        </Text>
      </View>
    </View>
  );
}

const styles = StyleSheet.create({
  container: {
    width: 180,
    height: 180,
    alignItems: "center",
    justifyContent: "center",
    alignSelf: "center",
  },
  ring: {
    position: "absolute",
    width: 160,
    height: 160,
    borderRadius: 80,
    borderWidth: 1.5,
  },
  sweepContainer: {
    position: "absolute",
    width: 160,
    height: 160,
    alignItems: "center",
    justifyContent: "flex-start",
  },
  sweep: {
    width: 1.5,
    height: 80,
    opacity: 0.5,
  },
  center: {
    width: 72,
    height: 72,
    borderRadius: 36,
    borderWidth: 2,
    alignItems: "center",
    justifyContent: "center",
  },
  peerCount: {
    fontSize: 24,
    fontWeight: "800",
  },
  peerLabel: {
    fontSize: 9,
    fontWeight: "700",
    letterSpacing: 1,
    marginTop: -2,
  },
});
