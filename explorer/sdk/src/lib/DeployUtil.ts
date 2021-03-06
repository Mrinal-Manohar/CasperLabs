import {
  Approval,
  Deploy,
  Signature
} from 'casperlabs-grpc/io/casperlabs/casper/consensus/consensus_pb';
import JSBI from 'jsbi';
import * as nacl from 'tweetnacl-ts';
import { ByteArray } from '../index';
import { Args, BigIntValue } from './Args';
import { protoHash } from './Contracts';

export enum ContractType {
  WASM = 'WASM',
  Hash = 'Hash',
  Name = 'Name'
}

// The following two methods definition guarantee that session is a string iff its contract type is ContractType.Name
// See https://stackoverflow.com/questions/39700093/variable-return-types-based-on-string-literal-type-argument for detail
export function makeDeploy(
  args: Deploy.Arg[],
  type: ContractType.Hash | ContractType.WASM,
  session: ByteArray,
  paymentWasm: ByteArray | null,
  paymentAmount: bigint | JSBI,
  accountPublicKey: ByteArray,
  dependencies?: Uint8Array[]
): Deploy;

export function makeDeploy(
  args: Deploy.Arg[],
  type: ContractType.Name,
  sessionName: string,
  paymentWasm: ByteArray | null,
  paymentAmount: bigint | JSBI,
  accountPublicKey: ByteArray,
  dependencies?: Uint8Array[]
): Deploy;

// If EE receives a deploy with no payment bytes,
// then it will use host-side functionality equivalent to running the standard payment contract
export function makeDeploy(
  args: Deploy.Arg[],
  type: ContractType,
  session: ByteArray | string,
  paymentWasm: ByteArray | null,
  paymentAmount: bigint | JSBI,
  accountPublicKey: ByteArray,
  dependencies: Uint8Array[] = []
): Deploy {
  const sessionCode = new Deploy.Code();
  if (type === ContractType.WASM) {
    sessionCode.setWasm(session);
  } else if (type === ContractType.Hash) {
    sessionCode.setHash(session);
  }else{
    sessionCode.setName(session as string);
  }
  sessionCode.setArgsList(args);
  if (paymentWasm === null) {
    paymentWasm = Buffer.from('');
  }
  const payment = new Deploy.Code();
  payment.setWasm(paymentWasm);
  payment.setArgsList(Args(['amount', BigIntValue(paymentAmount)]));

  const body = new Deploy.Body();
  body.setSession(sessionCode);
  body.setPayment(payment);

  const header = new Deploy.Header();
  header.setAccountPublicKey(accountPublicKey);
  header.setTimestamp(new Date().getTime());
  header.setBodyHash(protoHash(body));
  // we will remove gasPrice eventually
  header.setGasPrice(1);
  header.setDependenciesList(dependencies);

  const deploy = new Deploy();
  deploy.setBody(body);
  deploy.setHeader(header);
  deploy.setDeployHash(protoHash(header));
  return deploy;
};

export const signDeploy = (
  deploy: Deploy,
  signingKeyPair: nacl.SignKeyPair
): Deploy => {
  const signature = new Signature();
  signature.setSigAlgorithm('ed25519');
  signature.setSig(
    nacl.sign_detached(deploy.getDeployHash_asU8(), signingKeyPair.secretKey)
  );

  const approval = new Approval();
  approval.setApproverPublicKey(signingKeyPair.publicKey);
  approval.setSignature(signature);

  deploy.setApprovalsList([approval]);

  return deploy;
};

export const setSignature = (
  deploy: Deploy,
  sig: ByteArray,
  publicKey: ByteArray
): Deploy => {
  const signature = new Signature();
  signature.setSigAlgorithm('ed25519');
  signature.setSig(sig);

  const approval = new Approval();
  approval.setApproverPublicKey(publicKey);
  approval.setSignature(signature);

  deploy.setApprovalsList([approval]);

  return deploy;
};
