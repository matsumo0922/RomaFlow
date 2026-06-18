package me.matsumo.romaflow.core.morphology

/**
 * momiji 同梱 IPADIC の sys.dic 生バイト列（base64 をデコードした MeCab バイナリ）を返す。
 *
 * 生バイトの取得元 [io.github.tokuhirom.momiji.ipadic.sys.SYS] は momiji-ipadic-code の
 * public 宣言だが、common metadata には api 露出していないため commonMain から直接は
 * 参照できない。そのため target ごとの actual で base64 をデコードして取得する。decode は
 * 一時的に数十 MB のバッファを確保するため、呼び出し側で結果を使い回すこと。
 */
internal expect fun loadSysDicBytes(): ByteArray
