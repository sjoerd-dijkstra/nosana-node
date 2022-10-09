(ns nosana-node.solana
  (:require [nosana-node.util :refer [hex->bytes] :as util]
            [cheshire.core :as json]
            [clojure.edn :as edn]
            [taoensso.timbre :refer [log]]
            [clojure.core.async :as async :refer [<!! <! >!! put! go go-loop >! timeout take! chan]]
            [cheshire.core :as json]
            [clj-http.client :as http]
            [clojure.java.io :as io])
  (:import
   [org.p2p.solanaj.utils ByteUtils]
   [org.p2p.solanaj.rpc RpcClient Cluster]
   [org.bitcoinj.core Utils Base58 Sha256Hash]
   [java.io ByteArrayInputStream]
   [java.util Arrays]
   [java.util.zip Inflater InflaterInputStream]
   [org.p2p.solanaj.core Transaction TransactionInstruction PublicKey
    Account Message AccountMeta]))

(def rpc {:testnet "https://api.testnet.solana.com"
          :devnet  "https://api.devnet.solana.com"
          :mainnet "https://solana-api.projectserum.com"})

(defn rpc-call
  "Make a solana RPC call.
  This uses clj-http instead of solanaj's client."
  [method params network]
  (->
   (http/post (get rpc network)
              {:body         (json/encode {:jsonrpc "2.0" :id "1" :method method :params params})
               :content-type :json})
   ))

(defn get-balance [addr network]
  (->
   (rpc-call "getBalance" [(.toString addr)] network)
   :body (json/decode true) :result :value))

(defn get-token-balance [addr network]
  (->
   (rpc-call "getTokenAccountBalance" [(.toString addr)] network)
   :body (json/decode true) :result :value :uiAmount))

(defn get-account-data
  "Get the data of a Solana account as ByteArray."
  [addr network]
  (if-let [data (->
                 (rpc-call "getAccountInfo" [(.toString addr) {:encoding "base64"}] network)
                 :body
                 (json/decode true)
                 :result :value :data
                 first)]
    (-> data util/base64->bytes byte-array)
    (throw (ex-info "No account data" {:addr addr}))))

(defn create-pub-key-from-seed
  "Derive a public key from another key, a seed, and a program ID.
  Implementation web3.PublicKey.createWithSeed missing in solanaj"
  [^PublicKey from ^String seed ^PublicKey program-id]
  (doto (ByteArrayOutputStream.)
         (.writeBytes (.toByteArray from))
         (.writeBytes (.getBytes seed))
         (.writeBytes (.toByteArray program-id))))

(defn get-idl-address
  "Get the PublicKey associated with to IDL of a program.
  Anchor has a deterministic way to find the account holding the IDL
  for a specific program."
  [^PublicKey program-id]
  (let [base     (.getAddress (PublicKey/findProgramAddress [] program-id))
        buffer   (create-pub-key-from-seed base "anchor:idl" program-id)
        hash     (Sha256Hash/hash (.toByteArray buffer))]
    (PublicKey. hash)))

(def fetch-idl
  "Fetch the IDL associated with an on-chain program.
  Returns the IDL as a map with keywordized keys."
  (memoize
   (fn [^PublicKey program-id network]
     (let [acc-data  (-> program-id
                         get-idl-address
                         .toString
                         (get-account-data network))
           ;; skip discriminator and authority key
           idl-data  (Arrays/copyOfRange acc-data (+ 4 40) (count acc-data))
           in-stream (InflaterInputStream. (ByteArrayInputStream. idl-data))]
       (json/decode
        (String. (.readAllBytes in-stream) java.nio.charset.StandardCharsets/UTF_8)
        true)))))

(defn anchor-dispatch-id
  "Get the Anchor dispatch for a method

  Anchor uses an 8 byte dispatch ID for program methods, derived from the method
  name: Sha256(<namespace>:<method>)[..8]

  For user defined methods the namespace is global."
  [method]
  (->> method (str "global:") util/sha256 (take 16) (reduce str)))

(defn idl-type->size
  "Get the size in bytes of an IDL data type.
  Input is an IDL type like \"u64\" or `{:array [\"u8\" 32]}`"
  [type]
  (cond
    (= type "u64")       8
    (= type "i64")       8
    (= type "u32")       4
    (= type "u8")        1
    (= type "publicKey") 40
    (:array type)
    (let [[inner-type length] (:array type)]
      (* length (idl-type->size inner-type)))
    (:vec type)
    (let [[inner-type length] (:vec type)]
      (* length (idl-type->size inner-type)))
    :else (throw (ex-info "Unkown IDL type " {:type type}))))


(def addresses
  {:token             (PublicKey. "TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA")
   :system            (PublicKey. "11111111111111111111111111111111")
   :rent              (PublicKey. "SysvarRent111111111111111111111111111111111")
   :clock             (PublicKey. "SysvarC1ock11111111111111111111111111111111")
   :metaplex-metadata (PublicKey. "metaqbxxUerdq28cj1RbAWkYQm3ybzjb6a8bt518x1s")})

(def nos-addr (PublicKey. "devr1BGQndEW5k5zfvG5FsLyZv1Ap73vNgAHcQ9sUVP"))
(def nos-jobs (PublicKey. "nosJhNRqr2bc9g1nfGDcXXTXvYUmxD4cVwy2pMWhrYM"))
(def nos-stake (PublicKey. "nosScmHY2uR24Zh751PmGj9ww9QRNHewh9H59AfrTJE"))
(def ata-addr (PublicKey. "ATokenGPvbdGVxr1b2hvZbsiqW5xWH25efTNsLJA8knL"))

(def nos-collection (PublicKey. "CBLH5YsCPhaQ79zDyzqxEMNMVrE5N7J6h4hrtYNahPLU"))

(defn get-metadata-pda
  "Finds the MetaPlex metadata address for an NFT mint
  See https://docs.metaplex.com/programs/token-metadata/changelog/v1.0"
  [mint-id]
  (PublicKey/findProgramAddress [(.getBytes "metadata")
                                 (.toByteArray (:metaplex-metadata addresses))
                                 (.toByteArray mint-id)]
                                metaplex-metadata-address))

(defn get-nos-market-pda
  "Find the PDA of a markets vault."
  [market]
  (PublicKey/findProgramAddress [(.toByteArray market)
                                 (.toByteArray nos-addr)]
                                nos-jobs))

(defn pda [seeds program]
  (.getAddress (PublicKey/findProgramAddress seeds program)))

(defn get-nos-stake-pda
  "Find the PDA of a stake for an account."
  [addr]
  (PublicKey/findProgramAddress [(.getBytes "stake")
                                 (.toByteArray nos-addr)
                                 (.toByteArray addr)]
                                nos-stake))

(defn get-ata
  "Find the Associated Token Account for an address and mint."
  [addr mint]
  (.getAddress
   (PublicKey/findProgramAddress [(.toByteArray addr)
                                  (.toByteArray token-program-id)
                                  (.toByteArray mint)]
                                 ata-addr)))

;; TEMP: account needed for enter instructions
(def enter-accs
  {"authority" (PublicKey. "9cqm92kXLEyiNdU2WrHznkdidEHHB7UCApJrKHvU5TpP")
   "market"    (PublicKey. "CH8NQN6BU7SsRaqcMdbZJsEG7Uaa2jLkfsJqJkQ9He8z")
   "vault"     (PublicKey. "XGGfV7zMhzrQAmxD4uhFUt2ddhfNfnGpYprtz6UuHDB")
   "stake"     (PublicKey. "27xwnefmARrp9GoKQRiEMk3YSXdM5WUTAidir8kGdLTB")
   "nft"       (PublicKey. "BSCogYjj6tAfK5S6wm6oGMda5s72qW3SJvbDvAV5sdQ2")
   "metadata"  (PublicKey. "6pYVk617FEPdgiPzrpNLRrq7L9c66y91AEUMJtLjkbEi")})

(defn build-idl-tx
  "Build a transaction using the IDL of a program.
  The IDL is fetched from the blockchain using the Anchor standard."
  [program-id ins accounts network]
  (let [idl           (fetch-idl program-id network)
        discriminator (hex->bytes (anchor-dispatch-id ins))
        ins           (->> idl :instructions (filter #(= (:name %) ins)) first)
        ins-keys      (java.util.ArrayList.)
        args-size     (reduce #(+ %1  (idl-type->size (:type %2))) 0 (:args ins))
        ins-data      (byte-array (+ 8 args-size))]
    (doseq [{:keys [name isMut isSigner]} (:accounts ins)]
      (when (not (contains? accounts name))
        (throw (Exception. "Missing required account for instruction")))
      (.add ins-keys (AccountMeta. (get accounts name) isSigner isMut)))
    (System/arraycopy discriminator 0 ins-data 0 8)
    ;; TODO: copy arguments into ins-data
    (let [txi (TransactionInstruction. program-id ins-keys ins-data)
        tx  (doto (Transaction.)
              (.addInstruction txi))]
      tx)))

(defn read-type
  "Reads a single IDL parameter of  `type` from byte array `data`.
  Starts reading at `ofs`. Type is as defined in the IDL, like
  `\"u64\"` or `{:vec \"publicKey\"}`. Returns a tuple with the number
  of bytes read and the clojure data."
  [data ofs type]
  (cond
    (= type "u64")       [8 (ByteUtils/readUint64 data ofs)]
    (= type "i64")       [8 (Utils/readInt64 data ofs)]
    (= type "u32")       [4 (Utils/readUint32 data ofs)]
    (= type "u8")        [1 (get data ofs)]
    (= type "publicKey") [32 (PublicKey/readPubkey data ofs)]
    (:vec type)
    (let [elm-count (Utils/readUint32 data ofs)
          elm-size  (idl-type->size (:vec type))
          type-size (+ 4 (* elm-size elm-count))]
      [type-size
       (for [i    (range elm-count)
             :let [idx (+ ofs 4 (* i elm-size))]]
         (PublicKey/readPubkey data idx))])

    :else (throw (ex-info "Unkown IDL type " {:type type}))))

(defn get-idl-account
  "Fetches and decodes a program account using its IDL."
  [program-id account-type addr network]
  (let [idl      (fetch-idl program-id network)
        acc-data (get-account-data addr network)
        {:keys [type fields]}
        (->> idl :accounts (filter #(= (:name %) account-type)) first :type)]
    (loop [ofs                 8
           [field & remaining] fields
           data                {}]
      (if field
        (let [[size value] (read-type acc-data ofs (:type field))]
          (recur (+ ofs size)
                 remaining
                 (assoc data (keyword (:name field)) value)))
        data))))

(defn send-tx [tx signers network]
  (let [client (RpcClient. (get rpc network))
        api (.getApi client)
        sig (.sendTransaction api tx signers)]
    sig))

(defn get-tx
  "Get transaction `sig` as keywordized map"
  [sig network]
  (-> (rpc-call "getTransaction" [sig "json"] network)
      :body
      (json/decode true)
      :result))

(defn await-tx<
  "Returns a channel that emits transaction `sig` when it finalizes."
  ([sig network] (await-tx< sig 1000 30 network))
  ([sig timeout-ms max-tries network]
   (log :trace "Waiting for Solana tx " sig)
   (go-loop [tries 0]
     (log :trace "Waiting for tx " tries)
     (when (< tries max-tries)
       (if-let [tx (get-tx sig network)]
         tx
         (do (<! (timeout timeout-ms))
             (recur (inc tries))))))))


;;=================
;; EXAMPLES
;;=================

;; (sol/fetch-idl "nosJhNRqr2bc9g1nfGDcXXTXvYUmxD4cVwy2pMWhrYM" :mainnet)


;; (def k (PublicKey. "nosJhNRqr2bc9g1nfGDcXXTXvYUmxD4cVwy2pMWhrYM"))

#_(def accs {"authority" k
             "market"    k
             "vault"     k
             "stake"     k
             "nft"       k
             "metadata"  k})

;; (sol/idl-tx idl "nosJhNRqr2bc9g1nfGDcXXTXvYUmxD4cVwy2pMWhrYM" "enter" accs)

;; (defn sol-finish-job-tx [job-addr ipfs-hash signer-addr network]
;;   (let [job-key (PublicKey. job-addr)
;;         get-addr #(-> nos-config network %)
;;         keys (doto (java.util.ArrayList.)
;;                (.add (AccountMeta. job-key false true))            ; job
;;                (.add (AccountMeta. (vault-ata-addr network) false true))     ; vault-ata
;;                (.add (AccountMeta. (get-addr :signer-ata) false true))    ; ataTo
;;                (.add (AccountMeta. signer-addr true false))        ; authority
;;                (.add (AccountMeta. token-program-id false false))  ; token
;;                (.add (AccountMeta. clock-addr false false))        ; clock
;;                )
;;         data (byte-array (repeat (+ 8 1 32) (byte 0)))
;;         ins-idx (byte-array (javax.xml.bind.DatatypeConverter/parseHexBinary "73d976b04fcbc8c2"))]
;;     (System/arraycopy ins-idx 0 data 0 8)
;;     (aset-byte data 8 (unchecked-byte (.getNonce (vault-derived-addr network))))
;;     (System/arraycopy (ipfs-hash->job-bytes ipfs-hash) 0 data 9 32)
;;     (let [txi (TransactionInstruction. (get-addr :job) keys data)
;;           tx (doto (Transaction.)
;;                (.addInstruction txi)
;;                )]
;;       tx)))
