import {readFileSync, writeFileSync} from 'node:fs';
import {pathToFileURL} from 'node:url';

/** Offline labels for recorded decisions, NOT a glucose/delivery simulator or treatment advice.
 * No Android dependency, network access, dosing implementation or live import path exists. */
export function analyse(records, options = {}) {
  const lower = options.lower ?? 3.5;
  const upper = options.upper ?? 6.0;
  if (!Number.isFinite(lower) || !Number.isFinite(upper) || lower < 3.5 || upper < lower)
    throw new Error('Research boundaries must be finite, independently specified, and ordered; lower >= 3.5 mmol/L.');
  if (!Array.isArray(records)) throw new Error('Expected an array of timestamped retrospective records.');
  let previous = -Infinity;
  const profiles = {};
  const rows = records.map(record => {
    const timestamp = Number(record.timestamp ?? record.date);
    const glucose = record.glucoseMmol ?? (Number.isFinite(record.sgv) ? record.sgv / 18.0182 : undefined);
    if (!Number.isFinite(timestamp) || timestamp <= previous || !Number.isFinite(glucose) || glucose <= 0)
      throw new Error('Records must have finite glucose values and strictly increasing timestamps; deduplicate first.');
    previous = timestamp;
    const inside = glucose >= lower && glucose <= upper;
    // A missing baseline proposal is UNKNOWN. Current glucose alone never establishes a safe dose.
    const smbKnown = typeof record.proposedSmb === 'boolean';
    const basalKnown = typeof record.proposedBasalIncrease === 'boolean';
    const profile = typeof record.profile === 'string' ? record.profile : 'unknown';
    const group = profiles[profile] ??= {samples: 0, inResearchRange: 0, belowResearchRange: 0, aboveResearchRange: 0};
    group.samples++;
    group.inResearchRange += Number(inside);
    group.belowResearchRange += Number(glucose < lower);
    group.aboveResearchRange += Number(glucose > upper);
    return {
      timestamp, glucoseMmol: glucose, profile, insideResearchRange: inside,
      hypotheticalSmbExcluded: smbKnown ? Boolean(options.withholdSmb && inside && record.proposedSmb) : null,
      hypotheticalBasalIncreaseExcluded: basalKnown ? Boolean(options.withholdBasal && inside && record.proposedBasalIncrease) : null,
      hypoglycaemiaProtection: record.hypoProtection ?? 'unknown; baseline trace required',
      // No counterfactual glucose or insulin is fabricated.
      physiologicalOutcome: 'not simulated'
    };
  });
  return {schema: 1, researchOnly: true, lower, upper,
    limitation: 'Descriptive sample counts and proposal masks only. Not time-weighted TIR, a causal profile comparison, a validated simulator, or dosing advice. Full baseline IOB/COB, predictions, sensitivity, activity, insulin kinetics and safety traces are required for a physiological replay.',
    profiles, rows};
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  const [input, output, lower, upper, ...flags] = process.argv.slice(2);
  if (!input || !output) throw new Error('Usage: node analyse.mjs input.json output.json lower upper [--withhold-smb] [--withhold-basal]');
  const result = analyse(JSON.parse(readFileSync(input, 'utf8')), {
    lower: lower === undefined ? undefined : Number(lower), upper: upper === undefined ? undefined : Number(upper),
    withholdSmb: flags.includes('--withhold-smb'), withholdBasal: flags.includes('--withhold-basal')
  });
  writeFileSync(output, JSON.stringify(result, null, 2));
}
