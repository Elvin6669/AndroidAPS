import {test} from 'node:test';
import assert from 'node:assert/strict';
import {analyse} from './analyse.mjs';
const baseline = [{timestamp: 1, glucoseMmol: 5, proposedSmb: true, proposedBasalIncrease: true, hypoProtection: {lowSuspend: true}}];
test('research boundaries and two exclusions are independent', () => {
  const result = analyse(baseline, {lower: 3.5, upper: 5.5, withholdSmb: true});
  assert.equal(result.rows[0].hypotheticalSmbExcluded, true);
  assert.equal(result.rows[0].hypotheticalBasalIncreaseExcluded, false);
  const basalOnly = analyse(baseline, {lower: 4, upper: 6, withholdBasal: true});
  assert.equal(basalOnly.rows[0].hypotheticalSmbExcluded, false);
  assert.equal(basalOnly.rows[0].hypotheticalBasalIncreaseExcluded, true);
});
test('baseline hypoglycaemia protection and source records are preserved', () => {
  const saved = JSON.stringify(baseline);
  assert.deepEqual(analyse(baseline, {withholdSmb: true}).rows[0].hypoglycaemiaProtection, baseline[0].hypoProtection);
  assert.equal(JSON.stringify(baseline), saved);
});
test('missing decisions stay unknown', () => {
  const row = analyse([{timestamp: 1, glucoseMmol: 5}], {withholdSmb: true}).rows[0];
  assert.equal(row.hypotheticalSmbExcluded, null);
  assert.equal(row.physiologicalOutcome, 'not simulated');
});
test('out of range decisions are not excluded by in-range mask', () => {
  assert.equal(analyse([{...baseline[0], glucoseMmol: 7}], {withholdSmb: true}).rows[0].hypotheticalSmbExcluded, false);
});
test('invalid values and unordered records fail closed', () => {
  assert.throws(() => analyse(baseline, {lower: 3.4}));
  assert.throws(() => analyse(baseline, {lower: 7, upper: 6}));
  assert.throws(() => analyse([...baseline, ...baseline]));
  assert.throws(() => analyse([{timestamp: 1, glucoseMmol: NaN}]));
});
test('Nightscout entries and descriptive profile groups are supported', () => {
  const result = analyse([{date: 1, sgv: 90, profile: 'A'}, {date: 2, sgv: 180, profile: 'B'}]);
  assert.equal(result.profiles.A.inResearchRange, 1);
  assert.equal(result.profiles.B.aboveResearchRange, 1);
  assert.equal(result.researchOnly, true);
});
