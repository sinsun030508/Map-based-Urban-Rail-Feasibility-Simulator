const EARTH_RADIUS_KM = 6371.0088;

export function distanceKm([lng1, lat1], [lng2, lat2]) {
  const dLat = ((lat2 - lat1) * Math.PI) / 180;
  const dLng = ((lng2 - lng1) * Math.PI) / 180;
  const a =
    Math.sin(dLat / 2) ** 2 +
    Math.cos((lat1 * Math.PI) / 180) *
      Math.cos((lat2 * Math.PI) / 180) *
      Math.sin(dLng / 2) ** 2;
  return EARTH_RADIUS_KM * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
}

/**
 * 좌표를 순서대로 이은 총 연장(km).
 * 서버(geo/Haversine.java)와 같은 식이라 저장 후 값이 달라지면 둘 중 하나가 틀린 것이다.
 */
export function totalLengthKm(points) {
  let sum = 0;
  for (let i = 1; i < points.length; i += 1) {
    sum += distanceKm(points[i - 1], points[i]);
  }
  return sum;
}
