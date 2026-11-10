import 'dart:convert';
import 'dart:io';

import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:path_provider/path_provider.dart';

/// Where remote-access secrets live: SSH passwords and private keys, relay
/// device credentials. Never SharedPreferences.
///
/// Injectable so state and tests can swap in [MemorySecretStore], and so the
/// relay package (`dsh_remote`) can be handed the same store.
abstract class SecretStore {
  Future<String?> read(String key);
  Future<void> write(String key, String value);
  Future<void> delete(String key);
}

/// Android Keystore-backed storage (flutter_secure_storage).
class SecureSecretStore implements SecretStore {
  SecureSecretStore([FlutterSecureStorage? storage]) : _storage = storage ?? const FlutterSecureStorage();

  final FlutterSecureStorage _storage;

  /// Every key is namespaced so remote secrets never collide with app settings.
  static String _k(String key) => 'remote.$key';

  @override
  Future<String?> read(String key) => _storage.read(key: _k(key));

  @override
  Future<void> write(String key, String value) => _storage.write(key: _k(key), value: value);

  @override
  Future<void> delete(String key) => _storage.delete(key: _k(key));
}

/// Plain-file mirror in the app's private documents directory.
///
/// The Keystore is not a reliable single source of truth: on a cold start the
/// platform can hand `flutter_secure_storage` back before its key material is
/// ready, so a write that succeeded yesterday reads back as `null` today. The
/// relay account treats that as "never signed in" — it mints a new device
/// identity and deletes the refresh token, which costs a password sign-in and a
/// fingerprint confirmation on the computer. Keeping a second copy here makes
/// that failure recoverable.
///
/// The identity private key lands in the clear, inside the app sandbox.
/// Acceptable trade: the Host still pins the device fingerprint and the account
/// membership, so the file alone cannot impersonate the phone.
class FileSecretStore implements SecretStore {
  FileSecretStore([Future<Directory> Function()? directory])
      : _directory = directory ?? getApplicationDocumentsDirectory;

  final Future<Directory> Function() _directory;
  Map<String, String>? _cache;

  Future<File> _file() async => File('${(await _directory()).path}/remote_secrets.json');

  Future<Map<String, String>> _load() async {
    final cached = _cache;
    if (cached != null) return cached;
    var values = <String, String>{};
    try {
      final file = await _file();
      if (file.existsSync()) {
        final decoded = jsonDecode(await file.readAsString());
        if (decoded is Map) {
          values = {
            for (final entry in decoded.entries)
              if (entry.key is String && entry.value is String) entry.key as String: entry.value as String,
          };
        }
      }
    } on Object {
      values = <String, String>{}; // unreadable mirror: start over rather than fail the session
    }
    return _cache = values;
  }

  Future<void> _flush(Map<String, String> values) async {
    final file = await _file();
    await file.parent.create(recursive: true);
    final temp = File('${file.path}.tmp');
    await temp.writeAsString(jsonEncode(values), flush: true);
    await temp.rename(file.path);
    _cache = values; // only once the rename succeeded
  }

  @override
  Future<String?> read(String key) async => (await _load())[key];

  @override
  Future<void> write(String key, String value) async {
    final values = await _load();
    if (values[key] == value) return;
    await _flush({...values, key: value});
  }

  @override
  Future<void> delete(String key) async {
    final values = await _load();
    if (!values.containsKey(key)) return;
    await _flush({...values}..remove(key));
  }
}

/// Reads [primary] first and falls back to the [mirror].
///
/// A Keystore miss — either a throw or `null` — no longer means "no identity":
/// the mirror answers instead, so the device keeps its refresh token and its
/// fingerprint. Writes reach the mirror before the primary, so at least one
/// copy always survives.
class FallbackSecretStore implements SecretStore {
  FallbackSecretStore(this._primary, this._mirror);

  final SecretStore _primary;
  final SecretStore _mirror;

  @override
  Future<String?> read(String key) async {
    try {
      final value = await _primary.read(key);
      if (value != null) {
        if (await _mirror.read(key) != value) await _mirror.write(key, value); // backfill older installs
        return value;
      }
    } on Object {
      // Keystore not ready or ciphertext corrupt: fall through to the mirror.
    }
    return _mirror.read(key);
  }

  @override
  Future<void> write(String key, String value) async {
    await _mirror.write(key, value);
    try {
      await _primary.write(key, value);
    } on Object {
      // A failed Keystore write must not fail the sign-in: the mirror holds it.
    }
  }

  @override
  Future<void> delete(String key) async {
    try {
      await _primary.delete(key);
    } on Object {
      // Ignore: the mirror delete below is what makes the removal effective.
    }
    await _mirror.delete(key);
  }
}

/// In-memory store for tests and previews.
class MemorySecretStore implements SecretStore {
  MemorySecretStore([Map<String, String>? initial]) : values = {...?initial};

  final Map<String, String> values;

  @override
  Future<String?> read(String key) async => values[key];

  @override
  Future<void> write(String key, String value) async => values[key] = value;

  @override
  Future<void> delete(String key) async => values.remove(key);
}
