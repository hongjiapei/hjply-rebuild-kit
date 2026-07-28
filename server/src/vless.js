const UUID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;

export function normalizeUuid(value) {
  const uuid = String(value || "").trim().toLowerCase();
  if (!UUID_RE.test(uuid)) throw new Error("UUID must be a UUIDv4 value");
  return uuid;
}

export function parseVlessHeader(input, expectedUuid) {
  const data = input instanceof Uint8Array ? input : new Uint8Array(input);
  if (data.byteLength < 24) throw new Error("Incomplete VLESS header");

  const expected = uuidBytes(normalizeUuid(expectedUuid));
  if (!timingSafeEqual(data.subarray(1, 17), expected)) throw new Error("Invalid UUID");

  const optionLength = data[17];
  const commandOffset = 18 + optionLength;
  if (data.byteLength < commandOffset + 4) throw new Error("Incomplete VLESS command");
  const command = data[commandOffset];
  if (command !== 1 && command !== 2) throw new Error("Unsupported VLESS command");

  const port = (data[commandOffset + 1] << 8) | data[commandOffset + 2];
  const addressType = data[commandOffset + 3];
  let offset = commandOffset + 4;
  let hostname;

  if (addressType === 1) {
    if (data.byteLength < offset + 4) throw new Error("Incomplete IPv4 address");
    hostname = Array.from(data.subarray(offset, offset + 4)).join(".");
    offset += 4;
  } else if (addressType === 2) {
    const length = data[offset];
    offset += 1;
    if (!length || data.byteLength < offset + length) throw new Error("Incomplete domain address");
    hostname = new TextDecoder().decode(data.subarray(offset, offset + length));
    offset += length;
  } else if (addressType === 3) {
    if (data.byteLength < offset + 16) throw new Error("Incomplete IPv6 address");
    const groups = [];
    for (let index = 0; index < 16; index += 2) {
      groups.push(((data[offset + index] << 8) | data[offset + index + 1]).toString(16));
    }
    hostname = groups.join(":");
    offset += 16;
  } else {
    throw new Error("Unsupported address type");
  }

  if (!port || !hostname) throw new Error("Invalid destination");
  return { version: data[0], command, hostname, port, payload: data.subarray(offset) };
}

function uuidBytes(uuid) {
  return Uint8Array.from(uuid.replaceAll("-", "").match(/.{2}/g), (hex) => Number.parseInt(hex, 16));
}

function timingSafeEqual(left, right) {
  if (left.byteLength !== right.byteLength) return false;
  let difference = 0;
  for (let index = 0; index < left.byteLength; index += 1) difference |= left[index] ^ right[index];
  return difference === 0;
}
